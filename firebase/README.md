# Firebase rules moved

The Cloud Storage rules for `gs://steam-pigeon.firebasestorage.app` now live in
[`fschroer/steam-pigeon-web`](https://github.com/fschroer/steam-pigeon-web) as
`storage.rules`, where `firebase deploy --only storage` publishes them with the site
(locator ADR "Flyers read their flights in a browser", 0042). Edit them there, not in the
console. The rules are unchanged: each signed-in user reads and writes only
`users/{their uid}/**`.
