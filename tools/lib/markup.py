"""Convert dictionary HTML into the app's small body markup.

Output vocabulary (see Architecture.md §9):
  block : <p> (may nest; renderer treats start/end as line breaks), <br>
  inline: <b> <i> <sup> <sub> <ruby>base<rt>reading</rt></ruby>
          <span class="pos|sense|ex|label|note|mark"> <a href="#ID|?KEY"> <img src="...">
Anything else is unwrapped (text kept) or dropped (text removed).
"""
import html
import re
from dataclasses import dataclass, field
from html.parser import HTMLParser
from typing import Callable

VOID = {"img", "br", "meta", "link", "hr", "input", "col", "wbr"}

DROP = ("drop",)
UNWRAP = ("unwrap",)


def emit(tag: str, **attrs: str) -> tuple:
    return ("emit", tag, attrs)


def text(value: str) -> tuple:
    return ("text", value)


# A rule receives (tag, attrs) and returns DROP, UNWRAP, emit(...) or text(...).
Rule = Callable[[str, dict], tuple]


@dataclass
class Link:
    target: str  # raw link target as found in the source


@dataclass
class ConvertResult:
    body: str
    links: list[str] = field(default_factory=list)
    images: list[str] = field(default_factory=list)


LINK_PLACEHOLDER = "\x00L{}\x00"


class _Converter(HTMLParser):
    def __init__(self, rule: Rule):
        super().__init__(convert_charrefs=True)
        self.rule = rule
        self.out: list[str] = []
        self.stack: list[tuple[str, str | None]] = []  # (source tag, emitted tag or None)
        self.drop_depth = 0
        self.links: list[str] = []
        self.images: list[str] = []

    def _render_start(self, tag: str, attrs: dict) -> str:
        attrs = dict(attrs)
        label = attrs.pop("_label", None)  # heading the source only showed elsewhere (e.g. a TOC)
        if label:
            return self._render_start(tag, attrs) + f'<span class="label">{html.escape(label, quote=False)}</span>'
        if tag == "a":
            idx = len(self.links)
            self.links.append(attrs["href"])
            return f'<a href="{LINK_PLACEHOLDER.format(idx)}">'
        if tag == "img":
            self.images.append(attrs["src"])
            return f'<img src="{html.escape(attrs["src"])}">'
        extra = "".join(f' {k}="{html.escape(v)}"' for k, v in attrs.items())
        return f"<{tag}{extra}>"

    def handle_starttag(self, tag, attrs):
        attrs = {k: (v or "") for k, v in attrs}
        void = tag in VOID
        if self.drop_depth:
            if not void:
                self.stack.append((tag, None))
                self.drop_depth += 1
            return
        action = self.rule(tag, attrs)
        kind = action[0]
        if kind == "drop":
            if not void:
                self.stack.append((tag, None))
                self.drop_depth = 1
            return
        if kind == "text":
            self.out.append(html.escape(action[1], quote=False))
            if not void:
                self.stack.append((tag, None))
                self.drop_depth = 1  # replacement text stands in for the content
            return
        if kind == "unwrap":
            if not void:
                self.stack.append((tag, None))
            return
        _, out_tag, out_attrs = action
        self.out.append(self._render_start(out_tag, out_attrs))
        if not void and out_tag not in VOID:
            self.stack.append((tag, out_tag))
        elif not void:
            self.stack.append((tag, None))

    def handle_startendtag(self, tag, attrs):
        if tag in VOID:
            self.handle_starttag(tag, attrs)
        else:
            self.handle_starttag(tag, attrs)
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        if tag in VOID:
            return
        # Close up to the matching open tag; ignore stray end tags.
        if not any(t == tag for t, _ in self.stack):
            return
        while self.stack:
            src, emitted = self.stack.pop()
            if self.drop_depth:
                self.drop_depth -= 1
            elif emitted:
                self.out.append(f"</{emitted}>")
            if src == tag:
                break

    def handle_data(self, data):
        if self.drop_depth:
            return
        data = re.sub(r"\s*\n\s*", "", data)
        if data:
            self.out.append(html.escape(data, quote=False))

    def finish(self) -> ConvertResult:
        self.close()
        while self.stack:
            _, emitted = self.stack.pop()
            if emitted and not self.drop_depth:
                self.out.append(f"</{emitted}>")
        body = "".join(self.out)
        body = re.sub(r"<(p|span|b|i|sup|sub)(?: [^>]*)?></\1>", "", body)  # empty elements
        body = re.sub(r"(<br>)+$", "", body)
        return ConvertResult(body.strip(), self.links, self.images)


def convert(source: str, rule: Rule) -> ConvertResult:
    conv = _Converter(rule)
    conv.feed(source)
    return conv.finish()


def resolve_links(body: str, links: list[str], resolve: Callable[[str], str]) -> str:
    """Replace link placeholders with final hrefs (`#<entry id>` or `?<search key>`)."""
    def sub(m):
        return html.escape(resolve(links[int(m.group(1))]))
    return re.sub("\x00L(\\d+)\x00", sub, body)


def plain_text(body: str) -> str:
    """Body markup -> plain text without ruby readings (for previews and stats)."""
    t = re.sub(r"<rt>.*?</rt>", "", body)
    t = re.sub(r"<[^>]+>", "", t)
    return html.unescape(t)
