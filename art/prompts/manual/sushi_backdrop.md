# Sushi bar backdrop: manual Gemini run

Attach, in this order:
1. `art/guides/default_zones_small.png` (the colour-zone layout)
2. `docs/skin/mocks/restaurant_level.jpeg` (style reference)

## Prompt 1: paint the backdrop

Image 1 is a colour-coded layout map of a portrait mobile game screen. Image 2 shows the art style and setting. Paint the empty background scene for this game screen in exactly the art style of image 2: a cozy Japanese sushi bar for cute animals, flat 2D illustration, soft cel shading, thick dark brown outlines, warm pastel palette, seen from directly above.

Keep every coloured zone of image 1 in exactly the same position, size and shape, and paint each one as follows:
- Magenta: an empty conveyor belt made of pale wooden slats with a light wooden rim. The thin magenta path is the incoming belt; the magenta ring is a closed loop belt. No plates and no food on the belts.
- Orange: a kitchen hatch with a short noren curtain; the incoming belt comes out of it.
- Cyan: the area inside the loop, a plain light wooden surface.
- Yellow: a light wooden sushi counter.
- Red: four short, empty wooden benches. No animals.
- Purple: four small, empty wooden ticket stands.
- Green with the dark green border: a plain, pale, light wooden counter top with a thin darker wood frame. Completely empty and calm: no grid, no lines, no objects, no pattern.
- White: a plain pale cream wall with nothing on it (the game draws its buttons here).
- Grey: background scenery for the edges of the screen: cream paper screens, light natural wood, a few small paper lanterns and noren curtains. Keep it pale and quiet.

Same portrait aspect ratio as image 1. No plates, no food, no animals, no tickets or cards, no buttons, no text, no numbers, no grid lines. Do not move, resize or reshape any zone.

## Prompt 2: trace it back into zones (same chat, after an accepted painting)

Now convert the image you just made into a flat colour map with exactly the same layout as image 1. Fill every area with one solid colour, no shading, no outlines, no texture: conveyor belts magenta (#FF00FF), kitchen hatch orange (#FF8C00), inside of the loop cyan (#00C8FF), counter yellow (#FFD700), benches red (#E0002A), ticket stands purple (#8000FF), board frame dark green (#005A28), board surface green (#00B050), the plain wall at the top white (#FFFFFF), and everything else grey (#C8C8C8). Trace the shapes exactly where they are in your painting, even where they differ from image 1.

## Save as
- Painting: `art/raw/sushi/backdrop_v1.png` (or .jpeg)
- Zone map from prompt 2: `art/raw/sushi/backdrop_v1_zones.png`
