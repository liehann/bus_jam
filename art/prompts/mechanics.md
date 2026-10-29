# Mechanic art: one piece per Gemini chat

Attach `docs/skin/mocks/restaurant_level.jpeg` (the Critter Café level mock with the meal tickets, image 1) to every
prompt below, one new chat per piece, and save each result as `art/raw/mechanics/<piece>.png` (the name after
"Save as"). The pieces are shared by every theme. What each mechanic does: docs/DESIGN.md, "Mechanics (v4)".

The pieces go through the same cutout as the theme kit sprites (art/tools/theme_kit.py `cutout`: every light,
neutral pixel is keyed out), so each prompt asks for a plain pure white background and no white or very pale grey
parts inside the art. Parts the app tints in the ticket or bench colour are drawn in cream, like the critters
(art/tools/critter_sprites.py tints cream).

## frozen

Image 1 is a mock of my mobile game. Using exactly its art style (flat 2D illustration, soft cel shading, thick dark brown outlines, warm pastel palette), draw a sprite sheet on a plain pure white background with these 4 separate items, spaced well apart, none touching: in the top row, three ice cubes side by side, each a translucent pale-blue block of ice shaped to cover one of the rounded meal tickets in image 1, horizontal rounded rectangles the size and shape of a 1-cell, a 2-cell and a 3-cell ticket, from left to right, all the same height, each with bevelled faces in deeper ice blue along its edges, bright pale-blue highlights on the bevels, a few thin frost cracks near its corners and a thick dark brown outline, the centre of each block left clear and empty for a number; in the bottom row, centred, an empty round number badge, a cream disc with a thick ring around its edge drawn in light cream and a thick dark brown outline, its middle empty, no number or text in it. Draw the ice in pale blue and icy cyan tones and the badge in cream, never pure white or very pale grey anywhere (the white background is keyed out), no white highlights larger than tiny glints. Seen from directly above like the tickets in image 1. No tickets underneath, no shadows, no text, nothing else. Wide image, 2:1.

Save as `frozen`

## tie

Image 1 is a mock of my mobile game. Using exactly its art style (flat 2D illustration, soft cel shading, thick dark brown outlines, warm pastel palette), draw a sprite sheet on a plain pure white background with these 2 separate items side by side, spaced well apart, none touching: on the left, one plain straight horizontal ribbon segment, the kind that links two of the meal tickets in image 1 together, a long flat band of soft rose-pink satin with darker pink shading along its edges and a thick dark brown outline along its top and bottom edges, the same width and the same shading all along its length so it can be repeated end to end, both ends cut straight across, no bow, no knot, no loops, no folds; on the right, a small round padlock badge, a rose-pink disc with a thick dark brown outline and a small brass padlock with a dark brown keyhole in its middle. Seen from directly above like the tickets in image 1. No tickets, no shadows, no text, nothing else. Wide image, 3:1.

Save as `tie`

## mystery

Image 1 is a mock of my mobile game. Using exactly its art style (flat 2D illustration, soft cel shading, thick dark brown outlines, warm pastel palette), draw a sprite sheet on a plain pure white background with these 2 separate items side by side, spaced well apart, none touching: on the left, one meal ticket like the tickets in image 1, a horizontal rounded rectangle 2 cells long in a muted warm grey with a slightly darker outline, a dashed divider between the cells, a chevron arrow at its front end and, instead of the animal icon, a cream disc with a big bold dark brown question mark; on the right, the cream disc with the bold dark brown question mark on its own. Seen from directly above like the tickets in image 1. No shadows, no other text, nothing else. Wide image, 2:1.

Save as `mystery`

## wide

Image 1 is a mock of my mobile game. Using exactly its art style (flat 2D illustration, soft cel shading, thick dark brown outlines, warm pastel palette), draw one single extra-wide meal ticket clipped into an extra-wide wooden ticket holder, the holder as wide as two of the ticket stands in image 1 placed side by side, so it spans two benches: a long flat wooden base with two small feet, one at each end, and a long slot holding a wide upright card. The card is plain cream with a thin darker cream border, a small double chevron at its right end and an empty cream disc for an animal icon at its left end. Same view as the ticket stands in image 1. Centred on a plain pure white background with a wide margin. No benches, no animals, no shadow, no text, nothing else. Wide image, 3:1.

Save as `wide`

## colour_bench

Image 1 is a mock of my mobile game. Using exactly its art style (flat 2D illustration, soft cel shading, thick dark brown outlines, warm pastel palette), draw a sprite sheet on a plain pure white background with these 2 separate items side by side, spaced well apart, none touching: on the left, one of the small low wooden counter tables from image 1 (the light wood table with a flat top and short legs that the animals sit at) with a café tablecloth draped over it, the cloth covering the top and hanging down over the front and sides with a scalloped hem, soft folds where it falls over the edges, the legs showing below it; on the right, the same tablecloth on its own, in the same draped shape and size, without the table. The tablecloth is drawn in a warm cream (around #F3E6C8) with slightly darker cream fold shading and a thick dark brown outline, never pure white or very pale grey anywhere. Same view as the tables in image 1, the same size in both. No animals, no tickets, no plates, no shadows, no text, nothing else. Wide image, 2:1.

Save as `colour_bench`

## turntable

Image 1 is a mock of my mobile game. Using exactly its art style (flat 2D illustration, soft cel shading, thick dark brown outlines, warm pastel palette), draw a sprite sheet on a plain pure white background with these 3 separate icons in a row, spaced well apart, none touching: each a small rotation symbol to sit around the animal icon on one of the meal tickets in image 1, a pair of curved arrows running clockwise that together form a circle, each arrow an arc of about half the circle with a small pointed arrowhead at its leading end and a small gap before the tail of the other arrow, drawn as solid dark brown ink strokes, the inside of the circle left empty; the three icons the same size and shape, differing only in stroke thickness: thin on the left, medium in the middle, thick on the right. Flat, front-on, no perspective. No ticket, no animal, no shadows, no text, nothing else. Square image.

Save as `turntable`

## gate

Image 1 is a mock of my mobile game. Using exactly its art style (flat 2D illustration, soft cel shading, thick dark brown outlines, warm pastel palette), draw a sprite sheet on a plain pure white background with these 2 separate items side by side, spaced well apart, none touching: on the left, a closed velvet-rope barrier, two short polished brass stanchion posts with round weighted bases and round ball tops, standing about one board cell of image 1 apart, with a thick deep-red velvet rope hooked to the top of both posts and hanging between them in a gentle sag; on the right, one single polished brass stanchion post with a round weighted base and a round ball top, the same post as in the barrier, standing alone with no rope. Seen from slightly above like the board in image 1, the same size in both. No arrows, no lights, no chevrons, no board, no tickets, no shadows, no text, nothing else. Wide image, 2:1.

Save as `gate`
