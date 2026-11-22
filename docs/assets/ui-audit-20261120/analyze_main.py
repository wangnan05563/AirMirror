from PIL import Image

p = r'D:\code\otherProjects\AirMirror\docs\assets\ui-audit-20261120\main-411.png'
im = Image.open(p).convert('RGB')
W, H = im.size
pts = {
    'bg_top': (20, 400),
    'bg_mid': (20, 1200),
    'title': (200, 730),
    'primary_center': (540, 1056),
    'secondary_center': (540, 1245),
    'privacy': (540, 1400),
    'link_privacy': (540, 1600),
    'link_about': (540, 1680),
}
for k, (x, y) in pts.items():
    print(k, im.getpixel((x, y)))


def lum(c):
    def f(x):
        x = x / 255
        return x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4
    return .2126 * f(c[0]) + .7152 * f(c[1]) + .0722 * f(c[2])


def cr(a, b):
    l1, l2 = lum(a), lum(b)
    return (max(l1, l2) + .05) / (min(l1, l2) + .05)


bg = (11, 31, 58)
pri = (23, 195, 178)
sec = (18, 49, 79)
txt = (255, 255, 255)
print('contrast primary text', round(cr(bg, pri), 2))
print('contrast secondary text', round(cr(txt, sec), 2))
print('contrast secondary body', round(cr((179, 255, 255), bg), 2))

row = [im.getpixel((x, 1056)) for x in range(0, W)]
for x in range(0, W):
    if row[x] != (11, 31, 58) and row[x] != (0, 0, 0):
        print('primary left edge', x, row[x])
        break
