from PIL import Image

p = r'D:\code\otherProjects\AirMirror\docs\assets\ui-audit-20261120\main-411.png'
im = Image.open(p).convert('RGB')
regions = {
    'title': (63, 681, 1017, 793),
    'subtitle': (63, 793, 1017, 930),
    'meta': (63, 1371, 1017, 1487),
    'privacy_body': (63, 1487, 1017, 1561),
    'privacy_link': (63, 1561, 1017, 1645),
    'about_link': (63, 1645, 1017, 1719),
}
for name, box in regions.items():
    crop = im.crop(box)
    colors = crop.getcolors(crop.width * crop.height)
    colors.sort(reverse=True)
    print(name, 'dominant', colors[:3])
    brightest = max(colors, key=lambda t: sum(t[1]))[1]
    print(name, 'brightest', brightest)
