# Gmail integratsiyasi: Google Cloud sozlash (bir martalik, ~10 daqiqa)

Ilova Gmail'ga Play Services orqali ulanadi (Identity / Authorization API). Buning uchun
Google Cloud'da Android turidagi OAuth client kerak. Hech qanday secret ilovaga yozilmaydi:
Google ilovaning package nomi va imzo SHA-1 bo'yicha tanib oladi.

## Kerakli qiymatlar

| Nima | Qiymat |
|---|---|
| Package (debug build) | `org.telegram.messenger.beta` |
| Package (release / standalone build) | `org.telegram.messenger.web` |
| SHA-1 (ikkala build uchun bitta keystore) | `17:EB:76:BF:C3:61:4C:90:06:8E:8E:5D:45:69:1E:CF:4F:2C:71:A9` |
| Scope'lar | `https://www.googleapis.com/auth/gmail.modify`, `https://www.googleapis.com/auth/gmail.send` |

SHA-1 ni qayta olish kerak bo'lsa:

```bash
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -list -v -keystore TMessagesProj/config/release.keystore -storepass android -alias androidkey | grep SHA1
```

## Qadamlar

1. https://console.cloud.google.com → yuqoridagi loyiha tanlagichdan **New project**. Nomi ixtiyoriy
   (masalan `Telegram Gmail`). Agar Firebase loyihasi (google-services.json) allaqachon bor bo'lsa,
   o'shani ishlatsa ham bo'ladi.
2. **APIs & Services → Library** → "Gmail API" ni qidirib **Enable**.
3. **APIs & Services → OAuth consent screen** (yangi UI'da "Google Auth Platform → Branding"):
   - User type: **External**.
   - App name, support email, developer email: o'zingizniki.
   - **Scopes → Add or remove scopes**: ro'yxatdan `.../auth/gmail.modify` va `.../auth/gmail.send`
     ni belgilang (filtrga `gmail` yozing). Saqlang.
   - **Test users**: o'z Gmail manzilingizni qo'shing.
4. **Muhim:** consent screen'ning asosiy sahifasida **Publish app** tugmasini bosib holatni
   **In production** ga o'tkazing. "Testing" holatida Google 7 kundan keyin ruxsatni bekor qiladi,
   bu sizning eski botingizdagi muammo. Verification talab qilsa, **bermang**: bir foydalanuvchi
   uchun kerak emas, ilovada bir marta "Google hasn't verified this app" ogohlantirishi chiqadi,
   **Advanced → Go to <app name> (unsafe)** orqali o'tib ketasiz.
5. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type: **Android**.
   - Name: `Telegram beta`, Package name: `org.telegram.messenger.beta`, SHA-1: yuqoridagi.
   - **Create**.
   - Yana bir marta xuddi shunday, Package name: `org.telegram.messenger.web`, Name: `Telegram web`.
   Android client uchun client ID ilovaga yozilmaydi, shunchaki mavjud bo'lishi kerak.

Shu bilan tayyor. Ilovada Settings → Mods → Gmail → "Connect" bosilganda Google hisob tanlash oynasi
chiqadi, ruxsat berilgach xatlar kela boshlaydi.

## Agar "Error 10 / DEVELOPER_ERROR" chiqsa

Package nomi yoki SHA-1 mos kelmagan. Qaysi build o'rnatilganini tekshiring (`.beta` yoki `.web`)
va Credentials'dagi client bilan solishtiring. O'zgartirgandan keyin Google tomonida 5–10 daqiqa
kutish kerak bo'lishi mumkin.
