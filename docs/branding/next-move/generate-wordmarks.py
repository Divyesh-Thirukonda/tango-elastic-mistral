"""Rebuild Tango's outlined wordmarks from a locally supplied Rubik Bold TTF.

Usage: python generate-wordmarks.py [--font /path/to/Rubik-Bold.ttf]
Requires fontTools. Defaults to the kit's licensed local Rubik Bold font.
"""

from argparse import ArgumentParser
from pathlib import Path
from xml.sax.saxutils import escape

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.ttLib import TTFont


MARK = """  <defs>
    <path id="t" d="M26 28H102V46H75V100H53V46H26Z"/>
    <clipPath id="t-clip"><use href="#t"/></clipPath>
    <mask id="seam" maskUnits="userSpaceOnUse" x="0" y="0" width="128" height="128">
      <rect width="128" height="128" fill="white"/>
      <path d="M64 28V46C72 52 72 64 64 73C56 82 56 94 64 100" stroke="black" stroke-width="2"/>
    </mask>
  </defs>
  <g clip-path="url(#t-clip)" mask="url(#seam)">
    <use href="#t" fill="#A7B8FF"/>
    <path d="M0 0H64V46C72 52 72 64 64 73C56 82 56 94 64 100V128H0Z" fill="#F4B860"/>
  </g>"""


def main():
    parser = ArgumentParser()
    parser.add_argument("--font", type=Path, default=Path(__file__).parent / "fonts" / "Rubik-Bold.ttf")
    args = parser.parse_args()
    font = TTFont(args.font)
    glyph_set = font.getGlyphSet()
    cmap = font.getBestCmap()
    scale = 64 / font["head"].unitsPerEm
    advance = 128.0
    glyphs = []
    minimum_y = float("inf")
    maximum_y = float("-inf")
    last_right = advance
    for character in "tango":
        glyph = glyph_set[cmap[ord(character)]]
        path_pen = SVGPathPen(glyph_set)
        glyph.draw(path_pen)
        bounds_pen = BoundsPen(glyph_set)
        glyph.draw(bounds_pen)
        left, bottom, right, top = bounds_pen.bounds
        minimum_y = min(minimum_y, bottom)
        maximum_y = max(maximum_y, top)
        last_right = advance + right * scale
        glyphs.append((advance, path_pen.getCommands()))
        advance += glyph.width * scale - 2
    baseline = 64 + (minimum_y + maximum_y) * scale / 2
    width = round(last_right + 26, 3)
    asset_directory = Path(__file__).parent / "assets"
    for variant, fill, color_name in [
        ("dark", "#12151C", "ink"),
        ("light", "#F4F0E8", "warm paper"),
    ]:
        paths = "\n".join(
            f'    <path transform="translate({x:.3f} {baseline:.3f}) scale({scale:.6f} {-scale:.6f})" d="{escape(path, {chr(34): "&quot;"})}"/>'
            for x, path in glyphs
        )
        svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="128" viewBox="0 0 {width} 128" fill="none" role="img" aria-labelledby="title desc">
  <title id="title">Tango wordmark, {color_name}</title>
  <desc id="desc">The Tango paired T with lowercase Rubik Bold lettering converted to vector outlines.</desc>
{MARK}
  <g fill="{fill}" aria-label="tango">
{paths}
  </g>
</svg>
'''
        output = asset_directory / f"wordmark-{variant}.svg"
        output.write_text(svg)
        print(output)
    print(f"Visual wordmark bounds: width {width}, baseline {baseline:.3f}")


if __name__ == "__main__":
    main()
