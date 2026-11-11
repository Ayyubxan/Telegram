# Privacy Policy — Telegram Gmail (fork mod)

This is a personal, unofficial build of the Telegram Android client with a "Gmail as chats" module. It is not published on any app store and is used by its author only.

## What the Gmail module does

- Reads the signed-in Google account's Gmail inbox and shows each email as a message inside the app, one chat per sender.
- Sends emails on the user's behalf when the user writes a reply in such a chat.
- Changes labels (read/unread, inbox/archive, spam, trash) on the user's own emails when the user performs the matching action in the app.

## Data handling

- Email contents, attachments and the Gmail-to-message mapping are stored **only on the user's device**, in the app's private storage, next to the app's other local data.
- Nothing is sent to any server other than Google's Gmail API (`gmail.googleapis.com`) and, for sender icons, Google's public favicon service with the sender's domain name.
- No analytics, no third-party services, no data sharing of any kind.
- Removing the Google account from the app's settings ("Disconnect") stops all access; uninstalling the app deletes all stored data.

## Google API Services User Data Policy

Use and transfer of information received from Google APIs by this app adheres to the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including the Limited Use requirements.
