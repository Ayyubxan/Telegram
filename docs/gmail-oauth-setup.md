# Gmail integratsiyasi: Google Cloud sozlamasi

Ilova Gmail'ga OAuth 2.0 (authorization code + PKCE) orqali ulanadi. Login Google'ning sahifasida,
tizim brauzerida ochiladi, keyin `http://127.0.0.1:<port>` ga qaytadi, ilova shu manzilni bir lahza
tinglab turadi. Refresh token ilovaning shaxsiy sozlamalarida saqlanadi.

## Nega Android-turdagi client emas

Ilova `TMessagesProj/config/release.keystore` bilan imzolanadi. Bu rasmiy Telegram reposida 2019-yildan
beri yotgan ommaviy kalit (paroli `android`). Google Android client uchun package + SHA-1 juftligini
butun dunyo bo'yicha yagona talab qiladi, va `org.telegram.messenger.web` + shu SHA-1 ni allaqachon
kimdir band qilgan. Shuning uchun imzoga bog'liq bo'lmagan **Desktop app** turidagi client ishlatiladi.
Kelajakda o'z keystore'ga o'tilsa, Android client + Play Services oqimiga qaytish mumkin.

## Hozirgi holat (2026-11-10 da sozlangan)

| Nima | Qiymat |
|---|---|
| Cloud loyiha | `Telegram Gmail`, ID `telegram-gmail-514310` |
| Gmail API | yoqilgan |
| Consent screen | External, **In production** (7 kunlik token muddati yo'q) |
| Scope'lar | `gmail.modify`, `gmail.send` |
| Branding | homepage va privacy policy = GitHub repo (`docs/privacy.md`), domen `github.com` |
| OAuth client | Desktop app, nomi `Telegram browser flow` |

Client ID va secret `local.properties` da (git'ga tushmaydi):

```
GMAIL_OAUTH_CLIENT_ID=...apps.googleusercontent.com
GMAIL_OAUTH_CLIENT_SECRET=GOCSPX-...
```

`TMessagesProj/build.gradle` ularni `BuildConfig.GMAIL_OAUTH_CLIENT_ID` / `GMAIL_OAUTH_CLIENT_SECRET`
ga o'tkazadi. Qiymatlar bo'sh bo'lsa, Settings → Mods → Gmail → Connect xabar beradi.

## Yangi kompyuterda / clone'da

1. https://console.cloud.google.com → loyiha `Telegram Gmail` → Google Auth Platform → Clients.
2. `Telegram browser flow` client'ining ID sini oling. Secret ko'rinmaydi: yangi secret yarating
   ("Add secret") yoki yangi Desktop client yarating.
3. Ikkalasini `local.properties` ga yozing, build qiling.

## Ilovada ulash

Settings → Mods → Gmail → **Connect Google account**. Brauzer ochiladi, hisob tanlang,
"Google hasn't verified this app" chiqsa **Advanced → Go to Telegram Gmail (unsafe)**, ruxsat bering.
"Gmail connected" sahifasi chiqqach tab'ni yopib ilovaga qayting, inbox tusha boshlaydi.

## Muammolar

- **"Timed out waiting for the browser"**: 5 daqiqa ichida login tugamadi, qaytadan Connect bosing.
- **"invalid_grant" / Needs re-authorization**: Google ruxsatni bekor qilgan (parol o'zgargan, 6 oy
  ishlatilmagan, yoki https://myaccount.google.com/permissions da olib tashlangan). Qayta Connect.
- **Brauzer qaytmasa**: Chrome'da `127.0.0.1` ga o'tish bloklangan bo'lishi mumkin; boshqa brauzerni
  default qilib ko'ring.
