import json, sys, urllib.request, os
from pathlib import Path
ENDPOINT = "http://192.168.10.43:8787/mcp"
OUT = Path(os.environ["TEMP"]) / "reamicro-analysis/runtime/b4"
_next = [0]
def call(name, args, timeout=180):
    _next[0] += 1
    payload = json.dumps({"jsonrpc":"2.0","id":_next[0],"method":"tools/call",
                          "params":{"name":name,"arguments":args}}).encode()
    req = urllib.request.Request(ENDPOINT, data=payload,
        headers={"Accept":"application/json, text/event-stream","Content-Type":"application/json"})
    env = json.load(urllib.request.urlopen(req, timeout=timeout))
    text = env["result"]["content"][0]["text"]
    res = json.loads(text)
    if not res.get("ok"):
        raise RuntimeError(res.get("error") or text)
    return res["data"]
def save(label, data):
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{label}.json"
    with path.open("x", encoding="utf-8", newline="\n") as h:
        json.dump(data, h, ensure_ascii=False, indent=2); h.write("\n")
    print("Evidence:", path)
