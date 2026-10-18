## Rule: Always ask before modifying card scripts.

If a task requires creating or editing any file under `forge-gui/res/` (card scripts), you must NOT proceed directly.

### Required workflow:
1.  **STOP** and explain to the user what card script change is needed and why.
2.  **ASK** the user for explicit permission to proceed.
3.  If permission is granted, instruct the user to run the `/card-scripting.md` workflow to load the API reference before you make any changes.

Example response to user (in Russian, since the user communicates in Russian):
"Я собираюсь модифицировать карту «[название]», потому что [причина]. Если одобряете, запустите `/card-scripting.md`, чтобы я загрузил API-инструкции по работе с картами."