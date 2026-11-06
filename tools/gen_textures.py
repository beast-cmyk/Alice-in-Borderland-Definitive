"""Draws every texture Dead Man's Hand uses: original skins for the other players, the hunter's outfit and horse
mask, the motorcycle, and the item icons. Run: python3 tools/gen_textures.py (writes into src/main/resources)."""
import os
import random
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'deadmanshand', 'textures')


def save(img, *path):
    p = os.path.join(OUT, *path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)


def rect(img, x0, y0, x1, y1, col):
    for x in range(x0, x1):
        for y in range(y0, y1):
            img.putpixel((x, y), col)


def shade(col, f):
    r, g, b = col[:3]
    return (max(0, min(255, int(r * f))), max(0, min(255, int(g * f))), max(0, min(255, int(b * f))), 255)


def noisy(img, x0, y0, x1, y1, col, rng, amount=0.06):
    for x in range(x0, x1):
        for y in range(y0, y1):
            img.putpixel((x, y), shade(col, 1.0 + rng.uniform(-amount, amount)))


# ---- player-skin regions (classic 64x64 layout) ---------------------------------------------------------------
HEAD = dict(top=(8, 0, 16, 8), bottom=(16, 0, 24, 8), right=(0, 8, 8, 16), front=(8, 8, 16, 16), left=(16, 8, 24, 16), back=(24, 8, 32, 16))
HAT = {k: (a + 32, b, c + 32, d) for k, (a, b, c, d) in HEAD.items()}
BODY = dict(top=(20, 16, 28, 20), bottom=(28, 16, 36, 20), right=(16, 20, 20, 32), front=(20, 20, 28, 32), left=(28, 20, 32, 32), back=(32, 20, 40, 32))
JACKET = {k: (a, b + 16, c, d + 16) for k, (a, b, c, d) in BODY.items()}
RARM = dict(top=(44, 16, 48, 20), bottom=(48, 16, 52, 20), right=(40, 20, 44, 32), front=(44, 20, 48, 32), left=(48, 20, 52, 32), back=(52, 20, 56, 32))
LARM = dict(top=(36, 48, 40, 52), bottom=(40, 48, 44, 52), right=(32, 52, 36, 64), front=(36, 52, 40, 64), left=(40, 52, 44, 64), back=(44, 52, 48, 64))
RLEG = dict(top=(4, 16, 8, 20), bottom=(8, 16, 12, 20), right=(0, 20, 4, 32), front=(4, 20, 8, 32), left=(8, 20, 12, 32), back=(12, 20, 16, 32))
LLEG = dict(top=(20, 48, 24, 52), bottom=(24, 48, 28, 52), right=(16, 52, 20, 64), front=(20, 52, 24, 64), left=(24, 52, 28, 64), back=(28, 52, 32, 64))


def fill_part(img, part, col, rng, amount=0.05):
    for face, (x0, y0, x1, y1) in part.items():
        f = {'top': 1.08, 'bottom': 0.8, 'front': 1.0, 'back': 0.9, 'right': 0.94, 'left': 0.94}[face]
        noisy(img, x0, y0, x1, y1, shade(col, f), rng, amount)


def rows(part, face, y_from, y_to, col, img, rng=None):
    x0, y0, x1, y1 = part[face]
    for y in range(y0 + y_from, min(y1, y0 + y_to)):
        for x in range(x0, x1):
            img.putpixel((x, y), shade(col, 1.0 + (rng.uniform(-0.05, 0.05) if rng else 0)))


def limb_rows(part, y_from, y_to, col, img, rng):
    for face in ('front', 'back', 'left', 'right'):
        rows(part, face, y_from, y_to, col, img, rng)


SKIN_TONES = [(255, 220, 190), (240, 200, 165), (222, 176, 135), (198, 146, 104), (160, 112, 76), (118, 80, 54), (92, 62, 44)]
HAIR = [(24, 20, 18), (40, 30, 24), (70, 46, 28), (110, 74, 40), (160, 120, 70), (210, 180, 120), (120, 120, 125), (150, 40, 40), (40, 40, 90)]
CLOTH = [(200, 40, 40), (40, 70, 150), (30, 30, 34), (230, 230, 230), (60, 120, 70), (230, 180, 40), (120, 70, 150), (90, 90, 96),
         (210, 110, 40), (40, 140, 160), (150, 30, 70), (190, 160, 120), (70, 50, 40)]
PANTS = [(40, 50, 80), (30, 30, 34), (70, 70, 76), (110, 90, 70), (60, 70, 50), (40, 60, 110), (150, 140, 120)]
SHOES = [(20, 20, 20), (240, 240, 240), (80, 50, 30), (120, 30, 30), (40, 40, 70)]


def face(img, rng, skin, hair_col, style):
    x0, y0, x1, y1 = HEAD['front']
    eye_y = y0 + 4
    white = (245, 245, 245, 255)
    iris = rng.choice([(40, 30, 25), (60, 40, 25), (40, 60, 90), (30, 30, 30), (70, 90, 50)])
    iris = (*iris, 255)
    img.putpixel((x0 + 1, eye_y), white)
    img.putpixel((x0 + 2, eye_y), iris)
    img.putpixel((x0 + 5, eye_y), iris)
    img.putpixel((x0 + 6, eye_y), white)
    brow = shade(hair_col, 0.8)
    for x in (x0 + 1, x0 + 2, x0 + 5, x0 + 6):
        img.putpixel((x, eye_y - 1), brow)
    img.putpixel((x0 + 3, eye_y + 1), shade(skin, 0.88))
    img.putpixel((x0 + 4, eye_y + 1), shade(skin, 0.88))
    mouth = shade(skin, 0.62)
    for x in range(x0 + 3, x0 + 5 + (1 if rng.random() < 0.5 else 0)):
        img.putpixel((x, eye_y + 2), mouth)
    if rng.random() < 0.18:  # glasses
        frame = (30, 30, 30, 255)
        for x in range(x0, x1):
            img.putpixel((x, eye_y - 1), frame if x in (x0, x0 + 3, x0 + 4, x1 - 1) else img.getpixel((x, eye_y - 1)))
        for x in (x0 + 1, x0 + 2, x0 + 5, x0 + 6):
            img.putpixel((x, eye_y + 1 - 2), frame)


def hair(img, rng, col, style):
    # top always; front fringe, sides and back by style
    x0, y0, x1, y1 = HEAD['top']
    noisy(img, x0, y0, x1, y1, col, rng, 0.08)
    fringe = {'short': 1, 'long': 2, 'bob': 2, 'buzz': 0, 'cap': 0, 'tied': 1}[style]
    side = {'short': 3, 'long': 8, 'bob': 6, 'buzz': 1, 'cap': 2, 'tied': 3}[style]
    back = {'short': 4, 'long': 8, 'bob': 7, 'buzz': 2, 'cap': 4, 'tied': 6}[style]
    rows(HEAD, 'front', 0, fringe, col, img, rng)
    rows(HEAD, 'left', 0, side, col, img, rng)
    rows(HEAD, 'right', 0, side, col, img, rng)
    rows(HEAD, 'back', 0, back, col, img, rng)
    if style in ('long', 'bob'):
        # hair falls past the face on the sides of the front
        fx0, fy0, fx1, fy1 = HEAD['front']
        for y in range(fy0, fy0 + (7 if style == 'long' else 5)):
            img.putpixel((fx0, y), shade(col, 0.95))
            img.putpixel((fx1 - 1, y), shade(col, 0.95))
    if style == 'cap':
        cap = rng.choice(CLOTH)
        hx0, hy0, hx1, hy1 = HAT['top']
        noisy(img, hx0, hy0, hx1, hy1, cap, rng, 0.04)
        for face_name in ('front', 'left', 'right', 'back'):
            x0, y0, x1, y1 = HAT[face_name]
            for x in range(x0, x1):
                for y in range(y0, y0 + 2):
                    img.putpixel((x, y), shade(cap, 0.95))
    if style == 'tied':
        bx0, by0, bx1, by1 = HAT['back']
        for y in range(by0 + 3, by0 + 7):
            img.putpixel((bx0 + 3, y), shade(col, 0.9))
            img.putpixel((bx0 + 4, y), shade(col, 0.9))


def skin(seed):
    rng = random.Random(seed)
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    tone = rng.choice(SKIN_TONES)
    hair_col = rng.choice(HAIR)
    style = rng.choice(['short', 'short', 'long', 'bob', 'buzz', 'cap', 'tied'])
    top = rng.choice(CLOTH)
    pants = rng.choice(PANTS)
    shoes = rng.choice(SHOES)
    kind = rng.choice(['tee', 'tee', 'hoodie', 'jacket', 'shirt', 'tank'])
    fill_part(img, HEAD, tone, rng, 0.03)
    hair(img, rng, hair_col, style)
    face(img, rng, tone, hair_col, style)
    fill_part(img, BODY, top, rng)
    for arm in (RARM, LARM):
        fill_part(img, arm, tone, rng, 0.03)
        sleeve = {'tee': 4, 'hoodie': 12, 'jacket': 12, 'shirt': 12, 'tank': 0}[kind]
        limb_rows(arm, 0, sleeve, top, img, rng)
        if sleeve:
            rows(arm, 'top', 0, 4, top, img, rng)
        if kind == 'hoodie' or kind == 'jacket':
            limb_rows(arm, 10, 12, shade(top, 0.8), img, rng)
    for leg in (RLEG, LLEG):
        fill_part(img, leg, pants, rng)
        limb_rows(leg, 10, 12, shoes, img, rng)
        rows(leg, 'bottom', 0, 4, shoes, img, rng)
    fx0, fy0, fx1, fy1 = BODY['front']
    if kind == 'shirt':
        for y in range(fy0, fy1):
            img.putpixel((fx0 + 3, y), shade(top, 0.75))
            img.putpixel((fx0 + 4, y), shade(top, 0.75))
        img.putpixel((fx0 + 3, fy0), (240, 240, 240, 255))
        img.putpixel((fx0 + 4, fy0), (240, 240, 240, 255))
    elif kind == 'jacket':
        inner = rng.choice(CLOTH)
        for y in range(fy0, fy1 - 2):
            for x in (fx0 + 3, fx0 + 4):
                img.putpixel((x, y), shade(inner, 1.0))
    elif kind == 'hoodie':
        for x in range(fx0 + 2, fx0 + 6):
            img.putpixel((x, fy0 + 6), shade(top, 0.8))
            img.putpixel((x, fy0 + 7), shade(top, 0.8))
        img.putpixel((fx0 + 3, fy0 + 1), (230, 230, 230, 255))
        img.putpixel((fx0 + 4, fy0 + 1), (230, 230, 230, 255))
    elif kind == 'tee' and rng.random() < 0.5:
        mark = rng.choice(CLOTH)
        for x in range(fx0 + 2, fx0 + 6):
            for y in range(fy0 + 3, fy0 + 6):
                img.putpixel((x, y), shade(mark, 1.0))
    # belt
    for face_name in ('front', 'back', 'left', 'right'):
        x0, y0, x1, y1 = BODY[face_name]
        for x in range(x0, x1):
            img.putpixel((x, y1 - 1), shade(pants, 0.7))
    return img


def hunter():
    rng = random.Random(99)
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    suit = (34, 34, 38)
    vest = (24, 26, 22)
    glove = (14, 14, 14)
    fill_part(img, HEAD, (200, 170, 140), rng, 0.02)
    fill_part(img, BODY, suit, rng)
    fx0, fy0, fx1, fy1 = BODY['front']
    noisy(img, fx0, fy0 + 1, fx1, fy0 + 9, vest, rng, 0.08)
    for x in range(fx0, fx1):
        img.putpixel((x, fy0 + 4), (60, 60, 50, 255))
    for arm in (RARM, LARM):
        fill_part(img, arm, suit, rng)
        limb_rows(arm, 9, 12, glove, img, rng)
    for leg in (RLEG, LLEG):
        fill_part(img, leg, (28, 28, 30), rng)
        limb_rows(leg, 9, 12, (10, 10, 10), img, rng)
    # the collar
    for face_name in ('front', 'back', 'left', 'right'):
        x0, y0, x1, y1 = BODY[face_name]
        for x in range(x0, x1):
            img.putpixel((x, y0), (90, 90, 96, 255))
    img.putpixel((fx0 + 3, fy0), (220, 30, 30, 255))
    return img


def cuboid_faces(u, v, w, h, d):
    """UV faces of a model cuboid: name -> (x0, y0, x1, y1)."""
    return dict(top=(u + d, v, u + d + w, v + d), bottom=(u + d + w, v, u + d + 2 * w, v + d),
                west=(u, v + d, u + d, v + d + h), north=(u + d, v + d, u + d + w, v + d + h),
                east=(u + d + w, v + d, u + 2 * d + w, v + d + h), south=(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h))


def horse_mask():
    rng = random.Random(7)
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    brown = (122, 78, 44)
    dark = (70, 44, 26)
    skull = cuboid_faces(0, 0, 9, 9, 10)
    snout = cuboid_faces(0, 20, 6, 6, 7)
    for faces in (skull, snout):
        for name, (x0, y0, x1, y1) in faces.items():
            noisy(img, x0, y0, x1, y1, shade(brown, {'top': 1.08, 'bottom': 0.75}.get(name, 0.95)), rng, 0.09)
    # a white blaze down the snout, dark nostrils at its tip, a darker muzzle
    x0, y0, x1, y1 = snout['top']
    for y in range(y0, y1):
        img.putpixel((x0 + 2, y), (230, 226, 214, 255))
        img.putpixel((x0 + 3, y), (230, 226, 214, 255))
    x0, y0, x1, y1 = snout['north']
    noisy(img, x0, y0, x1, y1, dark, rng, 0.08)
    for (px, py) in ((x0 + 1, y0 + 3), (x0 + 4, y0 + 3), (x0 + 1, y0 + 4), (x0 + 4, y0 + 4)):
        img.putpixel((px, py), (16, 10, 8, 255))
    x0, y0, x1, y1 = skull['north']
    for y in range(y0 + 3, y1):
        img.putpixel((x0 + 4, y), (230, 226, 214, 255))
    # glassy black eyes on the sides, near the front
    for name, near_front_x in (('west', lambda f: f[2] - 3), ('east', lambda f: f[0] + 1)):
        f = skull[name]
        ex = near_front_x(f)
        for dx in range(2):
            for dy in range(2):
                img.putpixel((ex + dx, f[1] + 3 + dy), (12, 12, 14, 255))
        img.putpixel((ex, f[1] + 3), (200, 200, 210, 255))
    for (u, v) in ((40, 0), (40, 5)):
        for name, (x0, y0, x1, y1) in cuboid_faces(u, v, 2, 3, 1).items():
            noisy(img, x0, y0, x1, y1, brown, rng, 0.08)
    for name, (x0, y0, x1, y1) in cuboid_faces(28, 20, 2, 1, 8).items():
        noisy(img, x0, y0, x1, y1, (34, 22, 14), rng, 0.1)
    return img


def motorcycle():
    rng = random.Random(3)
    img = Image.new('RGBA', (64, 48), (0, 0, 0, 0))
    paint = (150, 26, 26)
    metal = (150, 150, 155)
    black = (22, 22, 24)
    for (u, v, w, h, d, col) in ((0, 0, 3, 4, 18, (40, 40, 44)), (0, 22, 5, 3, 6, paint), (22, 22, 5, 2, 7, black),
                                 (0, 31, 12, 1, 1, metal), (26, 31, 1, 7, 1, metal), (30, 31, 3, 3, 2, (230, 220, 160))):
        for name, (x0, y0, x1, y1) in cuboid_faces(u, v, w, h, d).items():
            noisy(img, x0, y0, x1, y1, shade(col, {'top': 1.1, 'bottom': 0.7}.get(name, 1.0)), rng, 0.07)
    wheel = cuboid_faces(42, 0, 2, 7, 7)
    for name, (x0, y0, x1, y1) in wheel.items():
        noisy(img, x0, y0, x1, y1, black, rng, 0.05)
        if name in ('west', 'east'):
            cx, cy = (x0 + x1 - 1) / 2.0, (y0 + y1 - 1) / 2.0
            for x in range(x0, x1):
                for y in range(y0, y1):
                    r = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
                    if r < 1.2:
                        img.putpixel((x, y), shade(metal, 1.0))
                    elif 1.8 < r < 2.4 and (x + y) % 2 == 0:
                        img.putpixel((x, y), shade(metal, 0.7))
    return img


SUITS = {
    'clubs': ["......", "..##..", ".####.", "..##..", "######", "######", "..##..", ".####."],
    'spades': ["..##..", ".####.", "######", "######", "######", "..##..", ".####.", "......"],
    'hearts': ["......", ".#..#.", "######", "######", ".####.", "..##..", "......", "......"],
    'diamonds': ["..##..", ".####.", "######", "######", ".####.", "..##..", "......", "......"],
}


def card(suit):
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    ink = (190, 20, 26, 255) if suit in ('hearts', 'diamonds') else (24, 24, 28, 255)
    for x in range(3, 13):
        for y in range(1, 15):
            corner = (x in (3, 12)) and (y in (1, 14))
            if not corner:
                img.putpixel((x, y), (246, 244, 236, 255))
    for x in range(3, 13):
        img.putpixel((x, 1 if x not in (3, 12) else 2), (200, 196, 186, 255))
    for x in range(3, 13):
        if x not in (3, 12):
            img.putpixel((x, 14), (186, 180, 170, 255))
    pattern = SUITS[suit]
    for row, line in enumerate(pattern):
        for col, ch in enumerate(line):
            if ch == '#':
                img.putpixel((5 + col, 4 + row), ink)
    img.putpixel((4, 2), ink)
    img.putpixel((11, 13), ink)
    return img


def phone():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for x in range(4, 12):
        for y in range(1, 15):
            img.putpixel((x, y), (30, 30, 34, 255))
    for x in range(5, 11):
        for y in range(3, 12):
            img.putpixel((x, y), (60, 160, 200, 255) if (x + y) % 5 else (120, 210, 230, 255))
    for x in range(6, 10):
        img.putpixel((x, 7), (250, 250, 250, 255))
    img.putpixel((7, 13), (90, 90, 96, 255))
    img.putpixel((8, 13), (90, 90, 96, 255))
    return img


def gun():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    body = (40, 40, 44, 255)
    light = (80, 80, 88, 255)
    pixels = []
    for x in range(2, 14):
        pixels.append((x, 6))
        pixels.append((x, 7))
    for x in range(4, 12):
        pixels.append((x, 5))
    for y in range(8, 13):
        pixels.append((8, y))
        pixels.append((9, y))
    for y in range(8, 11):
        pixels.append((5, y))
    for x, y in pixels:
        img.putpixel((x, y), body)
    for x in range(4, 12):
        img.putpixel((x, 5), light)
    img.putpixel((13, 6), (20, 20, 20, 255))
    return img


def match():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    stick = (214, 180, 120, 255)
    for i in range(10):
        img.putpixel((3 + i, 12 - i), stick if i % 3 else shade(stick, 0.85))
    for x, y in [(12, 3), (13, 2), (13, 3), (12, 2), (14, 2), (13, 1)]:
        img.putpixel((x, y), (170, 30, 30, 255))
    img.putpixel((13, 2), (210, 60, 50, 255))
    return img


def slip():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    paper = (236, 232, 218, 255)
    for x in range(3, 13):
        for y in range(4, 12):
            img.putpixel((x, y), paper)
    for x in range(3, 13):
        img.putpixel((x, 11), shade(paper, 0.8))
    for y, length in [(6, 7), (8, 5), (10, 6)]:
        for x in range(5, 5 + length):
            img.putpixel((x, y), (60, 60, 70, 255))
    img.putpixel((4, 5), (40, 40, 40, 255))
    return img


if __name__ == '__main__':
    for i in range(16):
        save(skin(1000 + i * 37), 'entity', 'contestant', 'skin_%d.png' % i)
    save(hunter(), 'entity', 'hunter.png')
    save(horse_mask(), 'entity', 'horse_mask.png')
    save(motorcycle(), 'entity', 'motorcycle.png')
    for s in SUITS:
        save(card(s), 'item', 'card_%s.png' % s)
    save(phone(), 'item', 'phone.png')
    save(gun(), 'item', 'gun.png')
    save(match(), 'item', 'match.png')
    save(slip(), 'item', 'slip.png')
    print('textures written')
