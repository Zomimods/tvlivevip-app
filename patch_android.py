import os, re, sys

# --- AndroidManifest: permissions, PiP, background service, no backup ---
mp = 'android/app/src/main/AndroidManifest.xml'
m = open(mp, encoding='utf-8').read()
perms = ['android.permission.FOREGROUND_SERVICE',
         'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
         'android.permission.POST_NOTIFICATIONS',
         'android.permission.WAKE_LOCK']
add = ''.join('    <uses-permission android:name="%s" />\n' % p for p in perms if p not in m)
m = m.replace('<application', add + '    <application', 1)
if 'supportsPictureInPicture' not in m:
    m = m.replace('<activity', '<activity android:supportsPictureInPicture="true"', 1)
if 'KeepAliveService' not in m:
    m = m.replace('</application>',
        '        <service android:name=".KeepAliveService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n    </application>', 1)
# Protection: block adb/cloud backup extraction of app data
if 'android:allowBackup' in m:
    m = re.sub(r'android:allowBackup="[^"]*"', 'android:allowBackup="false"', m, 1)
else:
    m = m.replace('<application', '<application android:allowBackup="false"', 1)
open(mp, 'w', encoding='utf-8').write(m)

# --- build.gradle: version number from the build counter + release signing (secret key) ---
gp = 'android/app/build.gradle'
g = open(gp, encoding='utf-8').read()
n = os.environ.get('RUN_NUMBER', '1')
g = re.sub(r'versionCode\s+\d+', 'versionCode ' + n, g, 1)
g = re.sub(r'versionName\s+"[^"]*"', 'versionName "1.0.' + n + '"', g, 1)
# The key and passwords come from GitHub Secrets via environment variables (never written to files).
sign = """    signingConfigs {
        release {
            storeFile file(System.getenv('KEYSTORE_PATH'))
            storePassword System.getenv('KEYSTORE_PASSWORD')
            keyAlias System.getenv('KEY_ALIAS')
            keyPassword (System.getenv('KEY_PASSWORD') ?: System.getenv('KEYSTORE_PASSWORD'))
        }
    }
"""
if 'signingConfigs' not in g:
    g = g.replace('    buildTypes {', sign + '    buildTypes {', 1)
    g = re.sub(r'(buildTypes\s*\{\s*release\s*\{)', r'\1\n            signingConfig signingConfigs.release', g, 1)
if 'signingConfig signingConfigs.release' not in g:
    sys.exit('ERROR: release signing was not applied to build.gradle')
open(gp, 'w', encoding='utf-8').write(g)
print('patched manifest and gradle, build', n)

# --- Anti-tamper: bake the expected signing-certificate hash into MainActivity ---
cert = os.environ.get('CERT_SHA256', '').strip().lower()
if not re.fullmatch(r'[0-9a-f]{64}', cert):
    sys.exit('ERROR: CERT_SHA256 missing or invalid (the signing key step must run first)')
ma = 'android/app/src/main/java/com/sami/tvlivevip/MainActivity.java'
src = open(ma, encoding='utf-8').read()
if '__CERT_SHA256__' not in src:
    sys.exit('ERROR: placeholder __CERT_SHA256__ not found in MainActivity.java')
src = src.replace('__CERT_SHA256__', cert)
open(ma, 'w', encoding='utf-8').write(src)
print('baked certificate hash into MainActivity')

# --- Google sign-in: the plugin reads the web client id from this string resource ---
sp = 'android/app/src/main/res/values/strings.xml'
st = open(sp, encoding='utf-8').read()
if 'server_client_id' not in st:
    st = st.replace('</resources>', '    <string name="server_client_id">770359396613-hnj1mu8crtgu7r8f259a3pumgnt4q8m3.apps.googleusercontent.com</string>\n</resources>')
    open(sp, 'w', encoding='utf-8').write(st)
print('added server_client_id')
