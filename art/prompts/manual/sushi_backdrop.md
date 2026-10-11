# Sushi bar backdrop: manual Gemini run (v2)

Attach, in this order:
1. `art/guides/default_zones_small.png` (the colour-zone layout)
2. `docs/skin/mocks/restaurant_level.jpeg` (style reference)

v1 notes (`art/raw/sushi/backdrop_v1*.jpeg`): the trace-back worked and lines up with the painting. Problems: the
dark green frame colour was left in the painting, the board was drawn in perspective as a trapezoid, and the
benches and ticket stands kept their red and purple label colours. v2 drops the frame zone (the app draws the
board), states that zone colours are labels only, and asks for a flat top-down view. After v2 the scenery
label changed from grey to olive: Gemini left the grey areas unpainted in both runs, reading grey as empty.

## Prompt 1: paint the backdrop

Image 1 is a colour-coded layout map of a portrait mobile game screen. Image 2 shows the art style and setting. Paint the empty background scene for this game screen in exactly the art style of image 2: a cozy Japanese sushi bar for cute animals, flat 2D illustration, soft cel shading, thick dark brown outlines, warm pastel palette.

The view is flat and straight down, like a floor plan: no perspective at all. Every rectangle in image 1 stays a rectangle with parallel sides, every straight edge stays straight, nothing gets narrower towards the top.

The colours in image 1 are only labels that say what goes where. None of these label colours may appear in the painting: paint each zone in its natural materials and colours, in exactly the same position, size and shape:
- Magenta: an empty conveyor belt made of pale wooden slats with a light wooden rim. The thin magenta path is the incoming belt; the magenta ring is a closed loop belt. No plates and no food on the belts.
- Orange: a kitchen hatch with a short cream noren curtain; the incoming belt comes out of it.
- Cyan: the area inside the loop, a plain light wooden surface.
- Yellow: a light natural wooden sushi counter.
- Red: four short, empty benches in natural light wood. No animals.
- Purple: four small, empty ticket stands in natural light wood, with nothing in them.
- Green: a plain, flat, pale wooden floor, one even calm surface that fills the whole green area right up to its edges. No table, no frame, no border, no edge line, no grid, no lines, no objects, no pattern.
- White: a plain pale cream wall with nothing on it (the game draws its buttons here).
- Olive: background scenery for the edges of the screen: cream paper screens, light natural wood, a few small paper lanterns and noren curtains. Keep it pale and quiet.

Same portrait aspect ratio as image 1. No plates, no food, no animals, no tickets or cards, no buttons, no text, no numbers, no grid lines. Do not move, resize or reshape any zone.

## Prompt 2: trace it back into zones (same chat, after an accepted painting)

Now convert the image you just made into a flat colour map with exactly the same layout as image 1. Fill every area with one solid colour, no shading, no outlines, no texture: conveyor belts magenta (#FF00FF), kitchen hatch orange (#FF8C00), inside of the loop cyan (#00C8FF), counter yellow (#FFD700), benches red (#E0002A), ticket stands purple (#8000FF), the plain floor area at the bottom green (#00B050), the plain wall at the top white (#FFFFFF), and everything else olive (#808000). Trace the shapes exactly where they are in your painting, even where they differ from image 1.

## Save as
- Painting: `art/raw/sushi/backdrop_v2.jpeg`
- Zone map from prompt 2: `art/raw/sushi/backdrop_v2_zones.jpeg`
(or just leave them in ~/Downloads and tell me)
