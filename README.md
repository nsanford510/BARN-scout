# Scout App

Android property scouting app for VPT.

## Building

1. Open in Android Studio
2. Add your Google Maps API key to `local.properties`:
   ```
   MAPS_API_KEY=your_api_key_here
   ```
3. Build and run on device/emulator

## Configuration

Set these in `local.properties` (defaults live in `local.defaults.properties` and are exposed as `BuildConfig` fields):

```
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_ANON_KEY=<publishable key>
WEB_APP_URL=https://<scanner-webgui>.vercel.app
```

## Sign-in

- Accounts are invite-only. An admin invites the user from the Supabase dashboard
  (Auth → Users → Invite), then approves them:
  `insert into public.user_roles (user_id, role) values ('<auth user id>', 'scout');`
- Signing in calls `has_app_access()`. Accounts without an `admin` or `scout` role are rejected,
  and the check runs again on each app start so revoked users are signed out.
- "Forgot password?" emails a link to `WEB_APP_URL/reset-password`, which is also where invite
  links let new users set a password.
- Sign out from the Stats tab (top-right icon). Tokens are kept out of device backups.
