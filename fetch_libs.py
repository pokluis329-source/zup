import urllib.request
import ssl
import os

ctx = ssl._create_unverified_context()
base = "https://repo1.maven.org/maven2"
dest = r"c:\Users\zero\StudioProjects\Zuppon\app\libs"

files = [
    ("com/squareup/retrofit2/retrofit/2.9.0/retrofit-2.9.0.jar",
     "retrofit-2.9.0.jar"),
    ("com/squareup/retrofit2/converter-gson/2.9.0/converter-gson-2.9.0.jar",
     "converter-gson-2.9.0.jar"),
    ("com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar",
     "okhttp-4.12.0.jar"),
    ("com/squareup/okhttp3/logging-interceptor/4.12.0/logging-interceptor-4.12.0.jar",
     "logging-interceptor-4.12.0.jar"),
    ("com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar",
     "okio-jvm-3.6.0.jar"),
]

for path, name in files:
    out = os.path.join(dest, name)
    if os.path.exists(out) and os.path.getsize(out) > 10000:
        print("SKIP", name)
        continue
    try:
        with urllib.request.urlopen(base + "/" + path, context=ctx) as r:
            data = r.read()
        with open(out, "wb") as f:
            f.write(data)
        print("OK  ", name, len(data), "bytes")
    except Exception as e:
        print("FAIL", name, e)
