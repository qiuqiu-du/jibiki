"""Gaiji (外字): characters and marks a dictionary prints as small pictures.

Each dictionary has a table, tools/dicts/gaiji/<dict_id>.tsv, for the pictures that stand for
something text can say exactly: section numbers, circled numbers, plain symbols. Every other
picture (rare kanji variants, music notation, icons) is kept as an image in the data pack's
`media` table, so nothing is guessed. SVG pictures are rendered to PNG here (rsvg-convert);
the app only decodes PNG and GIF.
"""
import html
import shutil
import subprocess
from pathlib import Path

from lib.markup import emit, raw

TABLES = Path(__file__).resolve().parent.parent / "dicts" / "gaiji"
SVG_HEIGHT = 48  # px; the app scales pictures to the text size


def load_table(dict_id: str) -> dict[str, str]:
    """name -> replacement body markup. Lines: name<TAB>markup<TAB>note; '# ' starts a comment."""
    out = {}
    path = TABLES / f"{dict_id}.tsv"
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("# "):
            continue
        name, markup, *_ = line.split("\t")
        out[name] = markup
    return out


def file_name(src: str) -> str:
    """Source path (gaiji/xx.svg, /gaiji/xx.svg, daijisen/xx.gif) -> name stored in the pack."""
    name = src.replace("\\", "/").split("/")[-1]
    return name[:-4] + ".png" if name.endswith((".svg", ".bmp")) else name


class Gaiji:
    """Replaces pictures by table text, or by an <img> whose bytes go into the pack."""

    def __init__(self, dict_id: str, load_files):
        """load_files(): {source file name: bytes}; called once, only if an image is needed."""
        self.table = load_table(dict_id)
        self._load_files = load_files
        self._files = None
        self.used: dict[str, int] = {}

    def action(self, src: str):
        """Rule action for an <img> with this source path."""
        base = src.replace("\\", "/").split("/")[-1]
        if base in self.table:
            return raw(self.table[base])
        name = file_name(src)
        self.used[name] = self.used.get(name, 0) + 1
        return emit("img", src=name)

    def text(self, src: str) -> str:
        """Plain text for places that can't hold markup (entry titles)."""
        base = src.replace("\\", "/").split("/")[-1]
        markup = self.table.get(base)
        return html.unescape(_strip_tags(markup)) if markup is not None else "〓"

    def media(self) -> dict[str, bytes]:
        """Pack name -> PNG/GIF bytes for every picture kept as an image."""
        if not self.used:
            return {}
        if self._files is None:
            self._files = {file_name(k): (k, v) for k, v in self._load_files().items()}
        out = {}
        for name in sorted(self.used):
            if name not in self._files:
                raise SystemExit(f"gaiji picture missing from the source: {name}")
            src_name, data = self._files[name]
            out[name] = svg_to_png(data) if src_name.endswith(".svg") else bmp_to_png(data) if src_name.endswith(".bmp") else data
        return out


def _strip_tags(markup: str) -> str:
    import re
    return re.sub(r"<[^>]+>", "", markup)


def bmp_to_png(bmp: bytes) -> bytes:
    """BMP (小学館日中) -> PNG with macOS's sips; the app decodes only PNG and GIF."""
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        src, dst = Path(d) / "in.bmp", Path(d) / "out.png"
        src.write_bytes(bmp)
        subprocess.run(["sips", "-s", "format", "png", str(src), "--out", str(dst)], capture_output=True, check=True)
        return dst.read_bytes()


def svg_to_png(svg: bytes) -> bytes:
    exe = shutil.which("rsvg-convert")
    if not exe:
        raise SystemExit("rsvg-convert not found: brew install librsvg")
    return subprocess.run([exe, "-h", str(SVG_HEIGHT), "-f", "png"], input=svg, capture_output=True, check=True).stdout
