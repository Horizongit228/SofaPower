from PIL import Image, ImageDraw
import math

SIZE = 256
img = Image.new("RGBA", (SIZE, SIZE), (9, 6, 15, 255))
p = img.load()

for y in range(SIZE):
    for x in range(SIZE):
        dx = x - SIZE / 2
        dy = y - SIZE / 2
        d = min(1.0, math.sqrt(dx * dx + dy * dy) / (SIZE * 0.66))
        t = 1.0 - d
        p[x, y] = (int(19 + 79 * t), int(10 + 29 * t), int(29 + 123 * t), 255)

d = ImageDraw.Draw(img)
d.ellipse((25, 25, 231, 231), fill=(104, 42, 214, 115), outline=(200, 164, 255, 220), width=5)
d.ellipse((45, 45, 211, 211), fill=(115, 48, 238, 150), outline=(232, 217, 255, 100), width=3)
d.arc((72, 70, 184, 190), start=318, end=222, fill=(255, 255, 255, 255), width=18)
d.rounded_rectangle((119, 52, 137, 130), radius=9, fill=(255, 255, 255, 255))

img.save("sofapower_hub.ico", sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
