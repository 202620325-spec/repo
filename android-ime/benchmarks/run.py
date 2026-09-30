#!/usr/bin/env python3
import argparse, csv, json, os, time, urllib.request
from pathlib import Path

SYSTEM = r'''You are an inline autocomplete engine running inside an Android keyboard.
The supplied text is text the user is currently writing. Predict only the continuation that belongs immediately at the cursor.
Your output is inserted literally into the user's text.
Do not answer the user's request. Do not explain. Do not greet. Do not add labels, quotation marks, or commentary.
Do not rewrite or repeat existing text. Do not output text that already exists to the right of the cursor. Do not output the activation marker /p.
Preserve the user's actual direction, language, technical terminology, specificity, tone, formatting, list structure, and existing constraints.
Do not arbitrarily simplify, generalize, reinterpret, or redesign the user's idea.
Infer missing details only when genuinely useful and strongly supported by context.
When multiple directions are plausible, produce a smaller continuation and give control back to the user. When intent is clear, a longer continuation is allowed.
Pay attention to text after the cursor. Return continuation text only.'''
BAD_STARTS = ("물론입니다", "물론이죠", "네,", "좋습니다", "Sure", "Certainly", "Of course", "Here is", "Here's", "다음은", "요청하신")

def user_prompt(case):
    before, after = case["before"], case.get("after", "")
    ko = sum('\uac00' <= c <= '\ud7a3' or '\u3131' <= c <= '\u318e' for c in before)
    en = sum(c.isascii() and c.isalpha() for c in before)
    lang = "ko-en-mixed" if ko and en else "ko" if ko else "en" if en else "unknown"
    return f"LANGUAGE: {lang}\\nMULTILINE: {chr(10) in before or chr(10) in after}\\nTEXT_BEFORE_CURSOR:\\n{before}\\n<CURSOR>\\nTEXT_AFTER_CURSOR:\\n{after}"

def call(api_key, model, case, temperature):
    payload = json.dumps({
        "model": model,
        "messages": [{"role":"system","content":SYSTEM},{"role":"user","content":user_prompt(case)}],
        "stream": False,
        "max_tokens": 96,
        "temperature": temperature,
        "top_p": 0.92,
        "reasoning_effort": "minimal"
    }).encode()
    req = urllib.request.Request("https://api.upstage.ai/v1/chat/completions", data=payload, method="POST", headers={"Authorization": f"Bearer {api_key}", "Content-Type":"application/json"})
    start = time.perf_counter()
    with urllib.request.urlopen(req, timeout=30) as r:
        body = json.load(r)
    latency = (time.perf_counter() - start) * 1000
    return body["choices"][0]["message"]["content"], latency

def heuristics(case, text):
    before, after = case["before"], case.get("after", "")
    repeated = any(before.endswith(text[:n]) for n in range(min(len(text), 80), 7, -1)) if text else False
    right_dup = bool(after and any(text.endswith(after[:n]) for n in range(min(len(text), len(after), 80), 1, -1)))
    assistanty = text.startswith(BAD_STARTS)
    too_long = len(text) > case.get("max_chars", 260)
    return {"assistanty": assistanty, "repeat": repeated, "right_dup": right_dup, "too_long": too_long, "auto_pass": not any((assistanty, repeated, right_dup, too_long))}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="solar-mini4")
    ap.add_argument("--temperature", type=float, default=0.18)
    ap.add_argument("--out", default="benchmark-results.csv")
    args = ap.parse_args()
    key = os.environ.get("UPSTAGE_API_KEY")
    if not key:
        raise SystemExit("Set UPSTAGE_API_KEY. The benchmark never stores the key.")
    cases = json.loads((Path(__file__).parent / "cases.json").read_text())
    rows = []
    for c in cases:
        try:
            text, latency = call(key, args.model, c, args.temperature)
            h = heuristics(c, text)
            row = {"id": c["id"], "model": args.model, "latency_ms": round(latency,1), "chars": len(text), **h, "completion": text,
                   "intent_preservation_1_5":"", "usefulness_1_5":"", "insertion_correctness_1_5":"", "style_preservation_1_5":"", "technical_precision_1_5":"", "accept_likelihood_1_5":"", "review_notes":""}
        except Exception as e:
            row = {"id": c["id"], "model": args.model, "error": repr(e)}
        rows.append(row)
        print(c["id"], row.get("completion", row.get("error")))
    fields = sorted({k for r in rows for k in r})
    with open(args.out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fields); w.writeheader(); w.writerows(rows)
    print("wrote", args.out)

if __name__ == "__main__": main()
