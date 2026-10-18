# Project Hierarchy

## Rule: Place code in the correct module. Never put general logic in platform-specific modules.

### Primary modules — where game logic lives

- **`forge-core`**: MTG rules engine, card mechanics, game state, fundamental game logic. If it implements a rule, a card interaction, or game state — it goes here.
- **`forge-game`**: Game session management, player interactions, game flow, multiplayer, game modes, matchmaking, state persistence. This is the bridge between `forge-core` and UI/networking. Do not duplicate core logic here — call it.
- **`forge-ai`**: Computer opponent logic only — decision-making for abilities, cards, and turn phases. Do not put general game rules here.
- **`forge-gui`**: UI components, rendering, card displays, player interaction. **Card scripting resources go in `forge-gui/res/`**.

### Platform-specific modules — thin backends only

- `forge-gui-android`, `forge-gui-ios`: libgdx backends for their platforms. They rely on `forge-gui-mobile` for GUI logic.
- `forge-gui-desktop`: Swing backend for desktop. UI layout and GUI-specific logic (e.g., attack/block arrows, stack target rendering) belongs here.
- `forge-gui-mobile`: libgdx GUI logic shared by mobile platforms.
- `forge-gui-mobile-dev`: libgdx desktop backend for developing mobile backends (LWJGL).

**Hard rule**: Never place general game logic, rules, or AI in any `forge-gui-*` platform module. If the code is not UI/rendering/platform-specific, it belongs in a primary module.

## Decision guide

| Task | Target module |
| :--- | :--- |
| New card mechanic or rule | `forge-core` |
| Game session, multiplayer, matchmaking | `forge-game` |
| AI decision-making | `forge-ai` |
| UI component or rendering | `forge-gui` (shared) or specific `forge-gui-*` (platform) |
| Card script | `forge-gui/res/` |

If the task does not clearly fit one row, ask the user before creating files.