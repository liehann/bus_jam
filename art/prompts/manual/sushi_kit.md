# Sushi bar kit: one piece per Gemini chat

Attach, in this order, to every prompt below (one new chat per piece):
1. `art/raw/sushi/backdrop_v2.jpeg` (style reference: the look to match)
2. `docs/skin/mocks/restaurant_level.jpeg` (setting reference)

Why pieces: the full-screen backdrop (v1, v2) puts the belt, loop and counter in roughly the right place, but
Gemini adds its own scenery wherever it likes (v2 painted a second room between the counter and the floor). The
app now assembles the screen itself at exact positions from these pieces, so nothing has to line up by luck:
- belts are drawn by code along the layout path, using the slat strip as a texture;
- floor, board surface and counter are tiled textures;
- kitchen hatch, bench, ticket stand and decorations are separate sprites placed from `art/layouts/default.json`.

Each prompt asks for a plain white background (the cutout script removes it) or a seamless texture (a script
checks and fixes the seams).

## Prompt 1: floor texture

Using exactly the art style of image 1 (flat 2D illustration, soft cel shading, warm pastel palette), paint a seamless, tileable square texture of a pale light-wood plank floor seen straight down. Planks run horizontally, soft subtle grain, very low contrast, no objects, no shadows, no outlines around the edge of the image. The left edge must continue seamlessly into the right edge and the top into the bottom. Square image.

Save as `art/raw/sushi/kit_floor.jpeg`

## Prompt 2: board surface texture

Using exactly the art style of image 1, paint a seamless, tileable square texture of a very pale, smooth light-wood counter top seen straight down: almost plain cream wood with a faint, soft grain, even lower contrast than a floor. No planks, no seams, no objects, no lines, no shadows. The edges must tile seamlessly. Square image.

Save as `art/raw/sushi/kit_board.jpeg`

## Prompt 3: conveyor belt strip

Using exactly the art style of image 1, paint one long, perfectly straight, horizontal strip of the conveyor belt from image 1 seen straight down: pale wooden slats running across the strip, with a light wooden rim and a thin dark brown outline along the top and bottom edges. Empty, no plates. The strip fills the whole width of the image and must tile seamlessly from its left edge to its right edge. Plain white background above and below the strip. Wide image, about 4 times wider than tall.

Save as `art/raw/sushi/kit_belt.jpeg`

## Prompt 4: counter strip

Using exactly the art style of image 1, paint one long, straight, horizontal strip of the light natural wooden sushi counter from image 1, in the same view as image 1: the flat counter top with its front edge and thin dark brown outlines. Empty: no ticket stands, no plates, nothing on it. It fills the whole width of the image and must tile seamlessly from left to right. Plain white background above and below. Wide image, about 4 times wider than tall.

Save as `art/raw/sushi/kit_counter.jpeg`

## Prompt 5: bench

Using exactly the art style of image 1, draw one single short, empty bench in natural light wood, in the same view as the benches in image 1, centred on a plain white background. Thick dark brown outline, no shadow on the ground, no animals, nothing on it, nothing else in the image. Wide image.

Save as `art/raw/sushi/kit_bench.jpeg`

## Prompt 6: ticket stand

Using exactly the art style of image 1, draw one single small, empty wooden ticket stand (a little wooden base with a slot that holds a card upright), in the same view as the ticket stands in image 1, centred on a plain white background. Thick dark brown outline, empty slot, no card, no shadow, nothing else in the image. Square image.

Save as `art/raw/sushi/kit_stand.jpeg`

## Prompt 7: kitchen hatch

Using exactly the art style of image 1, draw one single kitchen hatch with a short cream noren curtain, like the one in the top left of image 1, seen from the front, centred on a plain white background. The opening behind the curtain is dark. Thick dark brown outline, no belt, no shadow, nothing else in the image. Square image.

Save as `art/raw/sushi/kit_hatch.jpeg`

## Prompt 8: decoration sheet

Using exactly the art style of image 1, draw a sprite sheet on a plain white background with these separate items, spaced well apart in a loose grid, none touching or overlapping: a small round paper lantern hanging from a short cord, a wide cream noren curtain on a wooden rod, a narrow cream paper screen panel in a light wood frame, a small potted green plant, a small wooden sign with no writing. Same thick dark brown outlines and pastel colours, no shadows, no text.

Save as `art/raw/sushi/kit_decor.jpeg`
