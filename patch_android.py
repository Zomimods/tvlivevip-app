import os, re

# --- AndroidManifest: permissions, PiP, background service ---
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
open(mp, 'w', encoding='utf-8').write(m)

# --- build.gradle: version number from the build counter + release signing ---
gp = 'android/app/build.gradle'
g = open(gp, encoding='utf-8').read()
n = os.environ.get('RUN_NUMBER', '1')
g = re.sub(r'versionCode\s+\d+', 'versionCode ' + n, g, 1)
g = re.sub(r'versionName\s+"[^"]*"', 'versionName "1.0.' + n + '"', g, 1)
sign = """    signingConfigs {
        release {
            storeFile file('../../debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
    }
"""
if 'signingConfigs' not in g:
    g = g.replace('    buildTypes {', sign + '    buildTypes {', 1)
    g = re.sub(r'(buildTypes\s*\{\s*release\s*\{)', r'\1\n            signingConfig signingConfigs.release', g, 1)
open(gp, 'w', encoding='utf-8').write(g)
print('patched manifest and gradle, build', n)
