import urllib.request
import ssl
import os

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

libs = [
    ("https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar",
     r"app\libs\okio-jvm-3.6.0.jar"),
    ("https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.0/kotlin-stdlib-1.9.0.jar",
     r"app\libs\kotlin-stdlib-1.9.0.jar"),
]

for url, dest in libs:
    if os.path.exists(dest) and os.path.getsize(dest) > 10000:
        print(f"SKIP {dest} (ya existe)")
        continue
    try:
        with urllib.request.urlopen(url, context=ctx) as r:
            data = r.read()
        with open(dest, "wb") as f:
            f.write(data)
        print(f"OK   {dest} ({len(data)} bytes)")
    except Exception as e:
        print(f"FAIL {dest}: {e}")
