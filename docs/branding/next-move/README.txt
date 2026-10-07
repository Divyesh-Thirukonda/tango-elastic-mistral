TANGO / NEXT MOVE / BRAND SYSTEM 01

Start with tango-brand-guide.pdf for the complete concept and visual system.

The brand concept is "the next justified move."
Public tagline: Your intent. In motion.
Explanatory line: You lead. Tango finds the steps.

FILES
assets/mark-primary.svg: transparent paired mark, use on night ink.
assets/mark-one-color-light.svg: warm-paper monochrome mark for dark surfaces.
assets/mark-one-color-dark.svg: night-ink monochrome mark for light surfaces.
assets/app-icon.svg: full square launcher artwork, 128-unit construction.
assets/wordmark-light.svg and assets/wordmark-dark.svg: outlined Rubik lettering.
brand-tokens.json: color, typography, geometry, spacing and motion values.
brand-story.txt: positioning, origin, principles and product voice examples.
motion-demo.html: local motion study. Open it in a browser with this directory intact.
fonts/: Rubik Regular/Bold, IBM Plex Mono Regular and their SIL Open Font Licenses.

The Android launcher icon uses the paired T. The brand guide's UI illustration
is a proposed application of the system; the wider app UI is not redesigned here.

REPRODUCTION
The SVG marks have true transparent seams. Wordmark glyphs are outlines and
require no installed font. Minimum recommended symbol size is 32 px; minimum
wordmark width is 120 px. Keep 18 units clear around the visible 128-unit mark.
On light surfaces use the ink monochrome mark, especially at small sizes.

build-brand-guide.py rebuilds the PDF using Python and reportlab. It reads the
local fonts directory. generate-wordmarks.py rebuilds the outlined wordmarks
using Python and fonttools. Other exported assets do not require either package.

The puzzle connection comes from the user's naming inspiration. The identity
is original; the partnership and logo meanings are proposed brand metaphors.
Source: https://www.linkedin.com/help/linkedin/answer/a6861672/

Rubik source/license: https://github.com/google/fonts/tree/main/ofl/rubik
IBM Plex Mono source/license: https://github.com/google/fonts/tree/main/ofl/ibmplexmono

This identity was drawn as editable vector artwork. Image generation was not
used for this direction. The earlier stone concept is preserved separately.
