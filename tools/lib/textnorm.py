"""Kana and key normalization shared by every build script.

The Android app must apply exactly the same `search_key` normalization to user
input, otherwise lookups will miss. Keep the two implementations in sync.
"""
import re
import unicodedata

_KATA_FIRST, _KATA_LAST = 0x30A1, 0x30F6  # ァ..ヶ
_HIRA_OFFSET = 0x60

# Characters that dictionaries use as reading/notation markers, not as text.
_MARKS = re.compile(r"[・‐\-‑–=＝▽▼×△〈〉《》〔〕\s　]")

CIRCLED = {chr(0x24EA): 0, **{chr(0x2460 + i): i + 1 for i in range(20)}}  # ⓪①..⑳


def kata_to_hira(text: str) -> str:
    return "".join(
        chr(ord(c) - _HIRA_OFFSET) if _KATA_FIRST <= ord(c) <= _KATA_LAST - 2 else c
        for c in text
    )


def hira_to_kata(text: str) -> str:
    return "".join(
        chr(ord(c) + _HIRA_OFFSET) if 0x3041 <= ord(c) <= 0x3094 else c for c in text
    )


def strip_marks(text: str) -> str:
    return _MARKS.sub("", text)


def search_key(text: str) -> str:
    """Normalized lookup key: NFKC, notation marks removed, katakana -> hiragana."""
    return kata_to_hira(strip_marks(unicodedata.normalize("NFKC", text)))


def is_kana(text: str) -> bool:
    return bool(text) and all(
        0x3041 <= ord(c) <= 0x30FF or c in "ー〜" for c in text
    )


def has_japanese(text: str) -> bool:
    """True if the text contains kana or CJK ideographs (not just Latin letters)."""
    return any(
        0x3041 <= ord(c) <= 0x30FF or 0x3400 <= ord(c) <= 0x9FFF or 0xF900 <= ord(c) <= 0xFAFF
        or c in "々〆ヶ"
        for c in text
    )


_VOWEL_ROWS = {
    "a": "アカガサザタダナハバパマヤラワァャヵ",
    "i": "イキギシジチヂニヒビピミリヰィ",
    "u": "ウクグスズツヅヌフブプムユルゥュヴ",
    "e": "エケゲセゼテデネヘベペメレヱェヶ",
    "o": "オコゴソゾトドノホボポモヨロヲォョ",
}
_VOWEL = {c: v for v, row in _VOWEL_ROWS.items() for c in row}


def pron_key(reading: str) -> str:
    """Pronunciation-level key used to match NHK readings (セーブツ) with
    dictionary readings (せいぶつ): long vowels written as イ/ウ become ー."""
    kata = hira_to_kata(strip_marks(unicodedata.normalize("NFKC", reading)))
    kata = kata.replace("゚", "").replace("ヲ", "オ")  # nasal mark, particle wo
    out = []
    prev_vowel = None
    for c in kata:
        if c == "ー" or (c == "イ" and prev_vowel == "e") or (c == "ウ" and prev_vowel in ("o", "u")):
            out.append("ー")
            # keep prev_vowel so that オウウ etc. still collapse
            continue
        out.append(c)
        prev_vowel = _VOWEL.get(c)
    return "".join(out)


def expand_optional(spelling: str) -> list[str]:
    """Expand optional okurigana: 懸（け）橋 -> [懸け橋, 懸橋]."""
    parts = re.split(r"[（(]([^）)]*)[）)]", spelling)
    results = [""]
    for i, part in enumerate(parts):
        if i % 2 == 0:
            results = [r + part for r in results]
        else:
            results = [r + part for r in results] + results
    seen = []
    for r in results:
        if r and r not in seen:
            seen.append(r)
    return seen


def split_spellings(notation: str) -> list[str]:
    """Split a 【食べる・喰べる】 style notation into clean spellings."""
    notation = notation.strip().strip("【】")
    out = []
    for piece in re.split(r"[・／/、,]", notation):
        piece = re.sub(r"[▽▼×△〈〉《》‐]", "", piece).strip()
        for s in expand_optional(piece):
            if s and has_japanese(s) and s not in out:
                out.append(s)
    return out


def circled_to_numbers(text: str) -> list[str]:
    return [str(CIRCLED[c]) for c in text if c in CIRCLED]
