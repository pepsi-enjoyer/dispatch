// dispatch: Console TUI for Dispatch
//
// dispatch-e0k.1: PTY with claude via portable-pty + vt100 + ratatui
// dispatch-e0k.2: keyboard input forwarding to PTY
// dispatch-e0k.3: bd create integration from Rust
// dispatch-bgz.4: modal input model (command mode / input mode)
// dispatch-bgz.9: beads task lifecycle (create, assign, close, reopen)
// dispatch-bgz.12: config file and CLI subcommands

mod config;

use clap::{Parser, Subcommand};
use std::{
    io::{self, Read, Write},
    process::Command,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread,
    time::Duration,
};

#[derive(Parser)]
#[command(name = "dispatch", about = "Dispatch console TUI")]
struct Cli {
    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Subcommand)]
enum Commands {
    /// Generate a new pre-shared key and save it to config
    RegeneratePsk,
    /// Print the current pre-shared key
    ShowPsk,
    /// Print the config file path
    Config,
}

use crossterm::{
    event::{self, Event, KeyCode, KeyEvent, KeyModifiers},
    execute,
    terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen},
};
use portable_pty::{native_pty_system, CommandBuilder, PtySize};
use ratatui::{
    backend::CrosstermBackend,
    layout::{Constraint, Direction, Layout},
    style::{Color, Modifier, Style},
    text::{Line, Span, Text},
    widgets::{Block, Borders, Paragraph},
    Terminal,
};

const PTY_ROWS: u16 = 24;
const PTY_COLS: u16 = 80;

/// Input mode for the console (dispatch-bgz.4).
///
/// - `Command`: default mode; keystrokes control the console (navigation, quit, etc.).
/// - `Input`: keystrokes are forwarded directly to the active PTY.
///   The only key the console intercepts in this mode is Escape.
///   A double-Escape sends one literal Escape byte to the PTY.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum InputMode {
    Command,
    Input,
}

/// Run `bd create "{prompt}" -t task --json` and return the task ID.
fn bd_create_task(prompt: &str) -> Option<String> {
    let output = Command::new("bd")
        .args(["create", prompt, "-t", "task", "--json"])
        .output()
        .ok()?;

    if !output.status.success() {
        return None;
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    // bd returns a JSON array; extract the first id field
    let v: serde_json::Value = serde_json::from_str(stdout.trim()).ok()?;
    v.get(0)?.get("id")?.as_str().map(|s| s.to_owned())
}

/// Run `bd update {id} --claim --assignee {callsign} --status in_progress --json`.
fn bd_assign_task(id: &str, callsign: &str) -> bool {
    Command::new("bd")
        .args([
            "update",
            id,
            "--claim",
            "--assignee",
            callsign,
            "--status",
            "in_progress",
            "--json",
        ])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

/// Run `bd close {id} --reason "Completed" --json`.
fn bd_close_task(id: &str) -> bool {
    Command::new("bd")
        .args(["close", id, "--reason", "Completed", "--json"])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

/// Run `bd update {id} --status open --json` to reopen an abandoned task.
fn bd_reopen_task(id: &str) -> bool {
    Command::new("bd")
        .args(["update", id, "--status", "open", "--json"])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

/// Map a crossterm KeyEvent to the bytes that should be sent to the PTY.
fn key_to_pty_bytes(key: &KeyEvent) -> Vec<u8> {
    match key.code {
        KeyCode::Enter => b"\r".to_vec(),
        KeyCode::Backspace => vec![0x7f],
        KeyCode::Delete => b"\x1b[3~".to_vec(),
        KeyCode::Tab => b"\t".to_vec(),
        KeyCode::BackTab => b"\x1b[Z".to_vec(),
        KeyCode::Up => b"\x1b[A".to_vec(),
        KeyCode::Down => b"\x1b[B".to_vec(),
        KeyCode::Right => b"\x1b[C".to_vec(),
        KeyCode::Left => b"\x1b[D".to_vec(),
        KeyCode::Home => b"\x1b[H".to_vec(),
        KeyCode::End => b"\x1b[F".to_vec(),
        KeyCode::PageUp => b"\x1b[5~".to_vec(),
        KeyCode::PageDown => b"\x1b[6~".to_vec(),
        KeyCode::Esc => b"\x1b".to_vec(),
        KeyCode::Char(c) => {
            if key.modifiers.contains(KeyModifiers::CONTROL) {
                // Ctrl+A..Z -> 0x01..0x1a
                if c.is_ascii_alphabetic() {
                    let b = (c.to_ascii_lowercase() as u8) - b'a' + 1;
                    vec![b]
                } else {
                    let mut buf = [0u8; 4];
                    c.encode_utf8(&mut buf).as_bytes().to_vec()
                }
            } else {
                let mut buf = [0u8; 4];
                c.encode_utf8(&mut buf).as_bytes().to_vec()
            }
        }
        KeyCode::F(n) => match n {
            1 => b"\x1bOP".to_vec(),
            2 => b"\x1bOQ".to_vec(),
            3 => b"\x1bOR".to_vec(),
            4 => b"\x1bOS".to_vec(),
            5 => b"\x1b[15~".to_vec(),
            6 => b"\x1b[17~".to_vec(),
            7 => b"\x1b[18~".to_vec(),
            8 => b"\x1b[19~".to_vec(),
            9 => b"\x1b[20~".to_vec(),
            10 => b"\x1b[21~".to_vec(),
            11 => b"\x1b[23~".to_vec(),
            12 => b"\x1b[24~".to_vec(),
            _ => vec![],
        },
        _ => vec![],
    }
}

/// Convert a vt100 Cell color to ratatui Color.
fn vt100_color_to_ratatui(color: vt100::Color) -> Option<Color> {
    match color {
        vt100::Color::Default => None,
        vt100::Color::Idx(i) => Some(Color::Indexed(i)),
        vt100::Color::Rgb(r, g, b) => Some(Color::Rgb(r, g, b)),
    }
}

/// Render the vt100 screen into ratatui Lines.
fn screen_to_lines(screen: &vt100::Screen) -> Vec<Line<'static>> {
    let mut lines = Vec::new();
    for row in 0..screen.size().0 {
        let mut spans: Vec<Span<'static>> = Vec::new();
        let mut current_text = String::new();
        let mut current_style = Style::default();

        for col in 0..screen.size().1 {
            let cell = screen.cell(row, col).unwrap();
            let mut style = Style::default();

            if let Some(fg) = vt100_color_to_ratatui(cell.fgcolor()) {
                style = style.fg(fg);
            }
            if let Some(bg) = vt100_color_to_ratatui(cell.bgcolor()) {
                style = style.bg(bg);
            }
            if cell.bold() {
                style = style.add_modifier(Modifier::BOLD);
            }
            if cell.italic() {
                style = style.add_modifier(Modifier::ITALIC);
            }
            if cell.underline() {
                style = style.add_modifier(Modifier::UNDERLINED);
            }

            let ch = cell.contents();
            let ch = if ch.is_empty() { " ".to_string() } else { ch };

            if style == current_style {
                current_text.push_str(&ch);
            } else {
                if !current_text.is_empty() {
                    spans.push(Span::styled(current_text.clone(), current_style));
                    current_text.clear();
                }
                current_text = ch;
                current_style = style;
            }
        }
        if !current_text.is_empty() {
            spans.push(Span::styled(current_text, current_style));
        }
        lines.push(Line::from(spans));
    }
    lines
}

fn main() -> io::Result<()> {
    let cli = Cli::parse();

    match cli.command {
        Some(Commands::RegeneratePsk) => {
            let psk = config::regenerate_psk();
            println!("{psk}");
            return Ok(());
        }
        Some(Commands::ShowPsk) => {
            let cfg = config::load_or_create();
            println!("{}", cfg.auth.psk);
            return Ok(());
        }
        Some(Commands::Config) => {
            println!("{}", config::config_path().display());
            return Ok(());
        }
        None => {}
    }

    // Load (or create) config on startup.
    let cfg = config::load_or_create();
    let psk_short: String = cfg.auth.psk.chars().take(8).collect();

    // dispatch-bgz.9: beads task lifecycle
    // Slot 1 callsign is Alpha by convention (NATO phonetic alphabet, dispatch order)
    const CALLSIGN: &str = "Alpha";
    const PROMPT: &str = "PoC session: validate PTY + vt100 + ratatui";

    // 1. Create a typed task
    let task_id = bd_create_task(PROMPT);

    // 2. Assign the task to this agent slot
    if let Some(id) = &task_id {
        bd_assign_task(id, CALLSIGN);
    }

    // Info strip: callsign, task ID, PSK prefix
    let task_label = match &task_id {
        Some(id) => format!(" | task: {id}"),
        None => " | bd: unavailable".to_string(),
    };

    // Set up the PTY (dispatch-e0k.1)
    let pty_system = native_pty_system();
    let pair = pty_system
        .openpty(PtySize {
            rows: PTY_ROWS,
            cols: PTY_COLS,
            pixel_width: 0,
            pixel_height: 0,
        })
        .expect("failed to open PTY");

    // Spawn `claude` (or fall back to shell for testing)
    let cmd = CommandBuilder::new("claude");
    let _child = pair
        .slave
        .spawn_command(cmd)
        .or_else(|_| {
            // Fallback: open a shell so the PoC can be tested without claude installed
            let shell = if cfg!(windows) { "cmd" } else { "bash" };
            pair.slave.spawn_command(CommandBuilder::new(shell))
        })
        .expect("failed to spawn process");

    // PTY reader thread: feed bytes into vt100 parser; signal when PTY closes
    let screen: Arc<Mutex<vt100::Parser>> = Arc::new(Mutex::new(vt100::Parser::new(
        PTY_ROWS,
        PTY_COLS,
        0, // scrollback
    )));
    let screen_writer = Arc::clone(&screen);
    let mut pty_reader = pair.master.try_clone_reader().expect("clone reader");
    let child_exited = Arc::new(AtomicBool::new(false));
    let child_exited_writer = Arc::clone(&child_exited);

    thread::spawn(move || {
        let mut buf = [0u8; 4096];
        loop {
            match pty_reader.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    let mut parser = screen_writer.lock().unwrap();
                    parser.process(&buf[..n]);
                }
            }
        }
        child_exited_writer.store(true, Ordering::Relaxed);
    });

    // PTY writer (dispatch-e0k.2): keyboard input forwarding
    let mut pty_writer = pair.master.take_writer().expect("take writer");

    // 3. Deliver prefixed prompt to the agent
    // Format: "[Dispatch task {id}] {prompt_text}\r"
    if let Some(id) = &task_id {
        let prefixed = format!("[Dispatch task {id}] {PROMPT}\r");
        let _ = pty_writer.write_all(prefixed.as_bytes());
        let _ = pty_writer.flush();
    }

    // Set up ratatui terminal
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;

    // 4. Pane info strip: callsign, task ID, PSK prefix
    let title_base = format!("DISPATCH | {CALLSIGN}{task_label}  |  psk: {psk_short}...");

    // Modal input state (dispatch-bgz.4)
    let mut mode = InputMode::Command;
    // Track whether the previous key in Input mode was an Escape (for double-Escape passthrough).
    let mut last_was_escape = false;

    // Track exit reason to determine task close vs reopen (dispatch-bgz.9)
    let mut agent_terminated = false;

    loop {
        // Break if the child process exited (agent completed its work)
        if child_exited.load(Ordering::Relaxed) {
            break;
        }

        // Render
        {
            let parser = screen.lock().unwrap();
            let vt_screen = parser.screen();
            let lines = screen_to_lines(vt_screen);
            let text = Text::from(lines);

            // Mode indicator and help text vary by mode.
            let (mode_label, help_text, border_color) = match mode {
                InputMode::Command => (
                    " [COMMAND]",
                    " Enter/i: input mode  |  Ctrl+Q: quit",
                    Color::Green,
                ),
                InputMode::Input => (
                    " [INPUT]",
                    " Esc: command mode  |  Esc Esc: send Esc to PTY",
                    Color::Yellow,
                ),
            };

            let title = format!("{title_base}{mode_label}");

            terminal.draw(|frame| {
                let chunks = Layout::default()
                    .direction(Direction::Vertical)
                    .constraints([Constraint::Min(0), Constraint::Length(1)])
                    .split(frame.area());

                let block = Block::default()
                    .title(Span::styled(
                        title.as_str(),
                        Style::default()
                            .fg(border_color)
                            .add_modifier(Modifier::BOLD),
                    ))
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(border_color));

                let inner = block.inner(chunks[0]);
                frame.render_widget(block, chunks[0]);
                frame.render_widget(Paragraph::new(text), inner);

                let help = Span::styled(
                    help_text,
                    Style::default().fg(Color::DarkGray),
                );
                frame.render_widget(Paragraph::new(Line::from(help)), chunks[1]);
            })?;
        }

        // Poll for input
        if event::poll(Duration::from_millis(16))? {
            if let Event::Key(key) = event::read()? {
                match mode {
                    // ----------------------------------------------------------------
                    // Command mode: keystrokes control the console.
                    // ----------------------------------------------------------------
                    InputMode::Command => {
                        // Quit on Ctrl+Q (always available in command mode).
                        if key.code == KeyCode::Char('q')
                            && key.modifiers.contains(KeyModifiers::CONTROL)
                        {
                            agent_terminated = true;
                            break;
                        }

                        // Enter or 'i' transitions to Input mode.
                        if key.code == KeyCode::Enter
                            || (key.code == KeyCode::Char('i')
                                && key.modifiers.is_empty())
                        {
                            mode = InputMode::Input;
                            last_was_escape = false;
                            continue;
                        }

                        // TODO: additional command-mode bindings (pane navigation, etc.)
                        // will be added as part of the quad-pane layout epic.
                    }

                    // ----------------------------------------------------------------
                    // Input mode: keystrokes go to the PTY.
                    // Escape is the only key the console intercepts here.
                    // Double-Escape passes one literal Escape byte to the PTY.
                    // Radio voice commands are handled at a higher layer (always active).
                    // ----------------------------------------------------------------
                    InputMode::Input => {
                        if key.code == KeyCode::Esc {
                            if last_was_escape {
                                // Double-Escape: send one literal Escape to PTY, stay in Input mode.
                                let _ = pty_writer.write_all(b"\x1b");
                                let _ = pty_writer.flush();
                                last_was_escape = false;
                            } else {
                                // First Escape: wait to see if a second Escape follows.
                                last_was_escape = true;
                            }
                            continue;
                        }

                        if last_was_escape {
                            // Single Escape was pressed before this non-Escape key:
                            // switch to command mode and discard the current key.
                            mode = InputMode::Command;
                            last_was_escape = false;
                            continue;
                        }

                        let bytes = key_to_pty_bytes(&key);
                        if !bytes.is_empty() {
                            let _ = pty_writer.write_all(&bytes);
                            let _ = pty_writer.flush();
                        }
                    }
                }
            }
        }
    }

    // Restore terminal
    disable_raw_mode()?;
    execute!(terminal.backend_mut(), LeaveAlternateScreen)?;
    terminal.show_cursor()?;

    // 5. Task lifecycle close/reopen based on exit reason (dispatch-bgz.9)
    if let Some(id) = &task_id {
        if agent_terminated {
            // Agent was killed by user — reopen task so another agent can pick it up
            bd_reopen_task(id);
            println!("Agent terminated. Task {id} reopened as open.");
        } else {
            // Agent's PTY closed naturally — mark task complete
            bd_close_task(id);
            println!("Session complete. Task {id} closed.");
        }
    }

    Ok(())
}
