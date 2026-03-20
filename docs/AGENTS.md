# Agent Instructions

You are a dispatch worker agent. You have been assigned a task and should work in an isolated git worktree.

## Your Environment

- Your prompt includes your task ID (e.g. `t1`). Use it to name your worktree and branch.
- Other agents are working in parallel on their own worktrees. You will not conflict with them.
- The console manages task tracking and dispatch. You do not need to update any tracking files.

## Workflow

1. Read the task prompt delivered to your terminal.
2. Create your worktree and switch into it:
   ```bash
   git worktree add .dispatch/.worktrees/{task_id} -b task/{task_id} HEAD
   cd .dispatch/.worktrees/{task_id}
   ```
3. Do the work on your worktree branch.
4. Commit your changes with clear commit messages.
5. Merge your branch into main and clean up:
   ```bash
   cd "$(git rev-parse --path-format=absolute --git-common-dir)/.."
   git merge task/{task_id} --no-ff -m "merge task/{task_id}"
   git worktree remove .dispatch/.worktrees/{task_id} --force
   git branch -d task/{task_id}
   ```
6. Return to the prompt when done.

## Non-Interactive Shell Commands

**ALWAYS use non-interactive flags** with file operations to avoid hanging on confirmation prompts.

```bash
cp -f source dest           # NOT: cp source dest
mv -f source dest           # NOT: mv source dest
rm -f file                  # NOT: rm file
rm -rf directory            # NOT: rm -r directory
```

**Other commands that may prompt:**
- `scp` -- use `-o BatchMode=yes`
- `ssh` -- use `-o BatchMode=yes`
- `apt-get` -- use `-y` flag
- `brew` -- use `HOMEBREW_NO_AUTO_UPDATE=1`

## Completion

Your task is not done until you have merged your branch into main and cleaned up your worktree.

Before finishing:

1. **Commit all changes.** Run `git status` and ensure there are no unstaged or untracked files. Everything you want merged must be committed.
2. **Merge into main.** Navigate back to the repo root, merge your branch, remove the worktree, and delete the branch (see workflow above).
3. **Return to the prompt.** The console's completion detector watches for an idle prompt pattern. Once it sees you are idle, it triggers the next dispatch. Do not leave a command running or output streaming -- just stop and wait at the prompt.

If the merge fails due to conflicts, stop and return to the prompt. The console flags the conflict for manual review.

## Rules

- Do NOT modify `.dispatch/tasks.md` -- the console manages it.
- Do NOT push to remote -- merging into main locally is sufficient.
- Create your own worktree at the start and clean it up at the end.
- Commit all changes before merging.
