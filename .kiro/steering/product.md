# Product

Keep Going Elliptical (椭圆机续命) revives Mobi (莫比) smart ellipticals after the vendor shut
down its servers. The machines have no physical resistance buttons; the official app needs a
login that no longer works, so thousands of machines became unusable.

- Users: Mobi owners, mostly Chinese-speaking, non-technical. The UI follows the system
  language: English is the default (`res/values/strings.xml`), Simplified Chinese in
  `res/values-zh/strings.xml`. Code, comments, specs and submission docs are English
  (Chinese docs are mirrors).
- Every user-visible string, including log lines, is a string resource present in BOTH files.
  Never hard-code UI text in Kotlin. State that is shown later stores a resource id or
  `UiText`, not a resolved string, so a language change applies to it too.
- Core job: connect over BLE with no account and control resistance exactly like the official app.
- Secondary: per-workout time, distance, calories, heart rate, local history.
- No network access, no accounts, no analytics. Everything stays on the phone.
- Never ship code or assets from the official app. Only document protocol facts needed for
  interoperability, with the decompiled source location as evidence.
- When behaviour differs between official app versions, follow 4.5.17 and note 3.5.9.1.
