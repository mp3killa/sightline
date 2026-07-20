# Permissions

Sightline can let Claude read files, run commands, and change your code. This page describes exactly
what each control does, so you can pick a mode deliberately rather than by its name.

## The five modes

Set from the composer's mode chip, or Settings → Tools → Sightline.

| Mode | Chip | What it prompts for |
|---|---|---|
| `default` | **Ask** | Every tool call, before it runs |
| `acceptEdits` | **Auto-edit** | Commands only — file edits apply without asking |
| `plan` | **Plan** | Nothing runs; Claude proposes a plan instead |
| `auto` | **Auto** *(default)* | Claude decides, within the CLI's own safety rules |
| `bypassPermissions` | **Unrestricted** | Nothing. Everything runs immediately |

`auto` is the shipped default and is **model-gated** — it needs Sonnet or Opus, and silently falls back
to `default` on Haiku. The Health dialog tells you when that has happened, because a silent fallback is
exactly the kind of thing you should not have to guess about.

### Choosing one

- **Reviewing unfamiliar code, or working in something you can't easily revert** → `default`.
- **Normal work in a git repo you commit often** → `auto` or `acceptEdits`.
- **Working out what to do before doing it** → `plan`.
- **`bypassPermissions`** → only in a throwaway sandbox. It is flagged dangerous in the UI and it means
  it: an agent that misreads a request will act on the misreading with no chance to stop it.

Whatever you choose, **git is your real undo.** Commit before a large agent task.

## What the modes do *not* cover

Three guards apply regardless of mode. They are not part of the permission model because they protect
against things no mode should permit.

### Sensitive paths — always refused

`ide/PathAccessPolicy` refuses these outright, even in `bypassPermissions`:

```
~/.ssh  ~/.aws  ~/.gnupg  ~/.gcloud  ~/.kube  ~/.docker  ~/.claude
~/.password-store  ~/.config/git  ~/.config/gcloud
.env and .env.*   .netrc   .git-credentials   id_rsa / id_ed25519 / …
.npmrc  .pypirc  credentials
.idea/  .git/    any dotfile directly in $HOME
```

Writes **outside the project** are never silent either — they require an explicit confirmation showing
the full external path.

### Destructive device actions — always confirmed

`android/AndroidActionPolicy` gates `adb`. Anything that irreversibly discards something is confirmed
whatever the mode, and the confirmation names what is lost:

| Always confirmed | Why |
|---|---|
| `pm clear` | Erases databases, preferences, and any signed-in session |
| `uninstall` | The app and all of its data |
| `pm revoke` | A granted permission; the app may reset related state |
| `shell rm` | No trash on a device |
| `emu kill` | Unsaved emulator state |
| `reboot` | Everything running, including the debug session |
| `root` / `remount` / `disable-verity` | The device's verified-boot state |
| **anything unrecognised** | See below |

That last row is the important one. An `adb` command the gate does not recognise — including a quoted
`adb shell '…'` — is treated as **destructive**, not as safe. A chained command takes the worst risk of
its parts, so `adb devices && adb uninstall x` is not a device listing.

### Logcat redaction — always on

A device log carries auth tokens, emails, coordinates and device identifiers. `LogcatRedactor` removes
them before a capture can reach a prompt, and reports how many values it removed so you can tell a
redacted value from an absent one. It is not a setting. See [PRIVACY.md](../PRIVACY.md).

## Approving a single action

When a prompt appears you get three choices:

- **Allow** — this once.
- **Allow always** — this tool, for the rest of the session. Not persisted across restarts.
- **Deny** — the tool does not run. Claude is told, and continues.

A denial is *not* an error. The Activity Map marks a denied node distinctly and drops the optimistic
edge, so a denied edit never looks like it happened.

## Reviewing a change

File edits render as a diff before they apply — line counts, per-hunk numbering, side-by-side when
there's room. The approval preview shows the same diff, so you are never approving a change you can't
see. **Open file** and **Copy diff** are on the card.

## Turning things off

| Setting | Effect when off |
|---|---|
| `interactiveApproval` | No approval prompts; the CLI's own permission handling applies |
| `ideIntegration` | No IDE bridge — Claude can't read your selection, open files, or show native diffs |
| `androidFeatures` | No Android context, device actions, or logcat |
| `androidPersistCache` | *(off by default)* Nothing written to `.sightline/` |

## What Sightline never does

- Ask for, store, or transmit your Claude credentials. There is no login screen.
- Send anything anywhere except to the local `claude` process you started.
- Collect analytics or telemetry.
- Write outside the IDE's settings and — only if you enable it — `.sightline/`.
- Persist your conversation. Closing the project discards it.
