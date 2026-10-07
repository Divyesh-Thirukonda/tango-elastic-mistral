"""Generate the Tango identity guide from local, licensed fonts."""
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.colors import HexColor, Color
from reportlab.platypus import Paragraph
from reportlab.lib.styles import ParagraphStyle

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'tango-brand-guide.pdf'
W, H = 960, 640
INK, PAPER, SUN, MOON, GRAPHITE = '#12151C', '#F4F0E8', '#F4B860', '#A7B8FF', '#292E39'
MUTED_DARK, MUTED_LIGHT = '#646A75', '#B9BDC8'
for name, file in [('Rubik', 'Rubik-Regular.ttf'), ('RubikBold', 'Rubik-Bold.ttf'), ('Plex', 'IBMPlexMono-Regular.ttf')]:
    pdfmetrics.registerFont(TTFont(name, str(ROOT / 'fonts' / file)))
c = canvas.Canvas(str(OUT), pagesize=(W, H))
c.setTitle('Tango - Your intent. In motion. - Brand system 01')
c.setAuthor('Tango')

def rect(x, y, w, h, color, radius=0):
    c.setFillColor(HexColor(color))
    if radius:
        c.roundRect(x, y, w, h, radius, stroke=0, fill=1)
    else:
        c.rect(x, y, w, h, stroke=0, fill=1)

def text(value, x, y, size=16, color=INK, font='Rubik'):
    c.setFillColor(HexColor(color)); c.setFont(font, size); c.drawString(x, y, value)

def para(value, x, top, width, size=16, color=INK, bold=False, leading=None):
    style = ParagraphStyle('t', fontName='RubikBold' if bold else 'Rubik', fontSize=size,
        leading=leading or size*1.43, textColor=HexColor(color), spaceAfter=0)
    p = Paragraph(value, style)
    _, height = p.wrap(width, H)
    p.drawOn(c, x, top-height)
    return height

def page(number, section, dark=False):
    bg, fg = (INK, PAPER) if dark else (PAPER, INK)
    rect(0, 0, W, H, bg)
    text('tango', 56, 577, 22, fg, 'RubikBold')
    text(section.upper(), 170, 581, 10, MUTED_LIGHT if dark else MUTED_DARK, 'Plex')
    c.setStrokeColor(HexColor(GRAPHITE if dark else '#DCD8D0'))
    c.setLineWidth(.6); c.line(56, 554, 904, 554)
    text('BRAND SYSTEM 01 / OCT 2026', 56, 28, 9, MUTED_LIGHT if dark else MUTED_DARK, 'Plex')
    text(f'{number:02d} / 08', 847, 28, 9, MUTED_LIGHT if dark else MUTED_DARK, 'Plex')
    return bg, fg

def mark(x, y, size, bg=INK, mono=None, seam=True):
    c.saveState(); c.translate(x, y+size); c.scale(size/128, -size/128)
    p = c.beginPath(); p.moveTo(26,28); p.lineTo(102,28); p.lineTo(102,46)
    p.lineTo(75,46); p.lineTo(75,100); p.lineTo(53,100); p.lineTo(53,46); p.lineTo(26,46); p.close()
    c.clipPath(p, stroke=0, fill=0)
    q=c.beginPath(); q.moveTo(0,0);q.lineTo(64,0);q.lineTo(64,46)
    q.curveTo(72,52,72,64,64,73);q.curveTo(56,82,56,94,64,100)
    q.lineTo(64,128);q.lineTo(0,128);q.close()
    c.setFillColor(HexColor(mono or MOON));c.rect(0,0,128,128,fill=1,stroke=0)
    c.setFillColor(HexColor(mono or SUN));c.drawPath(q,fill=1,stroke=0)
    if seam:
        s=c.beginPath();s.moveTo(64,28);s.lineTo(64,46);s.curveTo(72,52,72,64,64,73);s.curveTo(56,82,56,94,64,100)
        c.setStrokeColor(HexColor(bg));c.setLineWidth(2);c.drawPath(s,stroke=1,fill=0)
    c.restoreState()

def end(): c.showPage()

# 01 / The identity
page(1, 'The next move', True)
text('Your intent.', 56, 432, 66, PAPER, 'RubikBold')
text('In motion.', 56, 354, 66, PAPER, 'RubikBold')
para('A phone agent that reads the moment, finds the next step, and checks where it lands.',56,266,480,20,PAPER)
text('YOU LEAD. TANGO FINDS THE STEPS.',56,133,11,SUN,'Plex')
mark(601,175,300)
text('01', 812, 114, 52, MOON, 'Plex')
end()

# 02 / Origin and meaning
page(2, 'The connection')
text('Attention becomes movement.',56,483,43,INK,'RubikBold')
para('The satisfying moment in a puzzle is when the next move becomes clear. That is the feeling Tango should bring to a phone.',56,443,780,19,INK)
cols=[(56,'01 / THE PUZZLE','Relationships make a move legible. Tango balances two symbols, uses same/opposite clues, and constrains what can sit next to what.'),
      (347,'02 / THE PARTNERSHIP','One person leads; another responds. The useful metaphor is attention, timing, and adjustment. Direction stays with the person.'),
      (638,'03 / THE AGENT','Your intention meets the visible screen. Tango observes, chooses a step, acts, and checks what changed before continuing.')]
for x, label, body in cols:
    text(label,x,339,11,MUTED_DARK,'Plex');para(body,x,313,252,16)
rect(56,96,848,105,INK)
para('The next justified move.',80,178,760,25,PAPER,True)
para('Progress comes from paying attention to both the goal and the moment.',80,137,760,16,PAPER)
text('Puzzle rules: LinkedIn Help / a6861672',56,62,10,MUTED_DARK,'Plex')
c.linkURL('https://www.linkedin.com/help/linkedin/answer/a6861672/',(56,58,430,77),relative=0)
end()

# 03 / Logo
page(3,'The paired T')
text('Two signals. One move.',56,483,44,INK,'RubikBold')
rect(56,156,350,281,INK)
for gx in range(56,407,22):
    c.setStrokeColor(HexColor(GRAPHITE));c.setLineWidth(.3);c.line(gx,156,gx,437)
for gy in range(156,438,22):c.line(56,gy,406,gy)
mark(69,131,326)
text('128-UNIT CONSTRUCTION / 2-UNIT SEAM',56,129,9,MUTED_DARK,'Plex')
items=[('The warm half','Your intention: the direction that starts the movement.'),
       ('The lunar half','The response: a step shaped by what the phone shows.'),
       ('The shared seam','An S-shaped passage for rhythm, feedback, and course correction.')]
for index,(label,body) in enumerate(items):
    top=430-index*85;text(label,470,top,18,INK,'RubikBold');para(body,470,top-16,400,14)
rect(470,89,126,102,INK);mark(482,90,102)
rect(620,89,126,102,INK);mark(632,90,102,INK,PAPER)
rect(770,89,126,102,PAPER);mark(782,90,102,PAPER,INK)
text('PRIMARY',470,68,9,MUTED_DARK,'Plex');text('ONE COLOR / LIGHT',620,68,9,MUTED_DARK,'Plex');text('ONE COLOR / DARK',770,68,9,MUTED_DARK,'Plex')
end()

# 04 / Color
page(4,'Color system',True)
text('Warm intent. Cool response.',56,483,42,PAPER,'RubikBold')
para('A night palette with two bright signals. Warm paper keeps long-form communication human.',56,440,800,18,PAPER)
swatches=[('NIGHT INK',INK,'Foundation'),('SOLAR AMBER',SUN,'Intent / primary action'),('LUNAR BLUE',MOON,'Response / observation'),('WARM PAPER',PAPER,'Content / light surface'),('GRAPHITE',GRAPHITE,'Supporting surface')]
for i,(name,col,role) in enumerate(swatches):
    x=56+i*170;rect(x,200,156,165,col)
    if i==0:
        c.setStrokeColor(HexColor('#555B66'));c.setLineWidth(.5);c.rect(x,200,156,165,fill=0,stroke=1)
    text(name,x,173,10,PAPER,'Plex');text(col,x,148,15,PAPER,'RubikBold');para(role,x,127,150,12,MUTED_LIGHT)
para('Keep most surfaces quiet. Use the two signal colors at meaningful moments, with text or shape labels so color never carries the state alone.',56,86,840,13,PAPER)
end()

# 05 / Typography and voice
page(5,'Type and voice')
text('Clear enough to act on.',56,483,46,INK,'RubikBold')
text('Rubik',56,416,44,INK,'RubikBold')
para('Headlines and product language. Geometric structure with a little softness. Regular for body copy, bold for decisions.',56,387,350,16)
text('IBM Plex Mono',504,422,27,INK,'Plex')
para('Status, timestamps, technical labels. Its measured rhythm supports the grid without turning every sentence into code.',504,387,380,16)
c.setStrokeColor(HexColor('#DCD8D0'));c.line(56,285,904,285)
text('THE VOICE: ATTENTIVE, SPECIFIC, CALM.',56,255,11,MUTED_DARK,'Plex')
samples=[('LISTENING','What should we do?'),('OBSERVING','Checking what is on screen.'),('UNCERTAIN','There are two Alexes. Which one?'),('PAUSED','Paused. Continue when you are ready.')]
for i,(label,copy) in enumerate(samples):
    x=56 if i%2==0 else 504;y=209-(i//2)*70;text(label,x,y,9,MUTED_DARK,'Plex');text(copy,x,y-27,16,INK)
text('Say what happened. Ask one useful question. Keep the person in charge.',56,67,14,INK,'RubikBold')
end()

# 06 / Motion and visual grammar
page(6,'Rhythm and behavior',True)
text('Observe. Act. Listen again.',56,483,43,PAPER,'RubikBold')
para('A two-beat cadence that changes with evidence. The pause is part of the choreography.',56,442,800,18,PAPER)
steps=[('01','OBSERVE',MOON,'A cool response appears.'),('02','ACT',SUN,'A warm step moves forward.'),('03','VERIFY',MOON,'The screen is read again.'),('04','CONTINUE',PAPER,'Only then, the next step.')]
for i,(n,label,col,copy) in enumerate(steps):
    x=56+i*216;c.setStrokeColor(HexColor(GRAPHITE));c.setLineWidth(1);c.line(x,263,x+176,263)
    rect(x,296,40,40,col,7);text(n,x+55,302,28,col,'Plex');text(label,x,234,11,PAPER,'Plex');para(copy,x,210,178,14,PAPER)
text('MOTION SPECIFICATION',56,127,10,MUTED_LIGHT,'Plex')
para('160 ms response / 240 ms transition / 400 ms complete cycle. Offset the two halves by one beat; bring them back into alignment. Pause settles immediately. Reduced motion uses static labeled states.',56,108,824,14,PAPER)
end()

# 07 / Product application
page(7,'In the product')
text('An identity with a job to do.',56,483,42,INK,'RubikBold')
para('Use the same logic in every touchpoint: a clear goal, a visible response, an easy way to lead again.',56,442,780,18)
rect(574,89,280,310,INK,22)
mark(590,331,60)
text('tango',648,360,24,PAPER,'RubikBold')
text('Your intent. In motion.',596,321,12,MUTED_LIGHT)
rect(596,214,235,75,GRAPHITE,12)
para('Find the registration form and save a draft.',612,270,201,15,PAPER)
text('CHECKING THE SCREEN',596,183,9,MOON,'Plex')
rect(596,112,235,42,SUN,12);text('Pause',686,127,15,INK,'RubikBold')
text('ILLUSTRATIVE UI / PROPOSED TREATMENT',574,66,8,MUTED_DARK,'Plex')
items=[('At the app icon','The paired T stays recognizable before the story is explained.'),('During a task','Cool indicates observation; warm indicates action. State labels always remain visible.'),('When something changes','Explain the new situation and the next step in concrete language.'),('When the user interrupts','Treat pause and correction as normal parts of working together.')]
for i,(title,body) in enumerate(items):
    top=366-i*72;text(title,56,top,18,INK,'RubikBold');para(body,56,top-14,425,14)
end()

# 08 / Use rules and deliverables
page(8,'Keep it in step')
text('One idea, carried all the way.',56,483,42,INK,'RubikBold')
para('The system should feel like a capable partner: precise about the next move, responsive when the situation changes.',56,443,815,18)
text('USE RULES',56,356,11,MUTED_DARK,'Plex')
rules=['Keep the mark flat; use the supplied colors or one-color variants.',
       'Keep clear space of 18 units around the 128-unit mark.',
       'Use at least 32 px for the primary symbol and 120 px for a wordmark.',
       'Use the curves and grid as supporting rhythm, never as a busy backdrop.',
       'Keep claims grounded in what the product observed and completed.']
for i,item in enumerate(rules):para(item,56,337-i*34,830,15)
rect(56,83,848,80,INK)
text('IN THIS KIT',80,137,10,MOON,'Plex')
para('Vector marks / outlined wordmarks / app icon / palette tokens / licensed fonts / voice guide / motion demo',80,116,790,14,PAPER)
text('Original identity inspired by the puzzle; the meaning here is a proposed brand metaphor.',56,62,10,MUTED_DARK)
end()
c.save()
print(OUT)
