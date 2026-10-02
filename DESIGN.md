---
name: Neko native mascot
description: The selected white lucky cat, adapted as an animated native 3D companion.
components:
  native-mascot-stage:
    height: "218dp"
---

# Design System: Neko native mascot

## Overview

This record is scoped to the native mascot correction made on 2 October 2026. The user explicitly selected the **left white lucky cat in `image.png`**. Its broad smiling face, pointed pink ears, closed smiling eyes, coral cheeks, seated body, raised paw on the viewer's right, green wave bib, red collar with a gold bell, and gold coin are the visual authority. Small discreet glasses carry the app's established character requirement into this adaptation.

The prior hybrid mascot with oversized cyan goggles was rejected. The current implementation follows the selected reference; this document does not establish user approval of the final adaptation. It records source geometry and behavior, without asserting a final render verdict or choosing a broader product identity.

**Key Characteristics:**

- A broad white face leads the seated silhouette.
- The lucky-cat expression and accessories remain recognizable beneath discreet glasses.
- Actual animated 3D geometry provides gentle head and paw movement.
- Native accessibility and reduced motion remain part of the character stage.

## Colors

The mascot uses warm porcelain fur, dark facial strokes, coral ears and cheeks, a green bib with pale waves, a red collar, gold bell and coin, and thin dark teal glasses. The material factors in `tools/build_neko_mascot.py` are the source of truth; PBR material values must not be replaced with sampled screenshot colors.

The surrounding Android interface continues to use semantic colors from `NekoTheme.kt` in both dark and light modes. The mascot's green bib and red collar are identity details rather than new interface accent tokens.

## Layout

The native conversation screen uses the stage height declared in the frontmatter. Android layout measurements use dp; text uses sp and respects font scaling. Preserve native insets and the existing minimum interactive target of 48dp.

The native camera is fixed at `0deg 82deg auto`, with a field of view of 30 degrees. Keep the entire seated silhouette and raised paw visible. The character stage does not expose pan, zoom, or an interaction prompt.

The browser preview remains a separate surface using the existing `preview/neko-mascot.png` still. It does not load or demonstrate the updated native GLB.

## Elevation & Depth

The native character consists of actual mesh geometry rendered from `app/src/main/assets/models/neko.glb` in an offline WebView using an intercepted local HTTPS asset origin. It is not an image mounted on a plane. The renderer uses a transparent background, a restrained warm halo, shadow intensity of 0.7, and exposure of 1.05.

## Shapes

The wide white head, pointed ears, short seated body and curved raised arm carry the selected lucky-cat silhouette. Closed smiling eyes and cheek markings follow the face's curvature. The gold coin is tall and oval, held beneath the resting paw.

The corrected glasses use a dedicated thin torus with tube factor 0.025 and lens scales of 0.162 by 0.150 in model coordinates. Their bridge and temples are thin strokes. Preserve the visible closed-eye expression and avoid turning the frames into oversized goggles.

The green triangular bib carries repeated pale seigaiha wave geometry clipped to its front. Current wave geometry sits at model z 0.456 with stroke radius 0.0065. The collar is one continuous red tube around the neckline rather than detached red pieces. These values describe the current asset generator; they are not Android layout dimensions.

## Components

### Native mascot stage

`NekoMascot3D.kt` loads the generated GLB and its local scripts. The generator is `tools/build_neko_mascot.py`; its additional `preview/neko.glb` output does not mean the browser preview consumes that file.

The animation named **A warm hello** combines a restrained paw wave and gentle head motion over a 3.5 second cycle. Playback speed is 0.82 at idle and 1.12 while replying. Reduced motion pauses the model and suppresses the speaking halo state. Playback also pauses when the document is hidden. The same actual model remains visible while paused.

Compose supplies a state-aware semantic description: “Neko, the white lucky cat with small glasses, is replying” while speaking, and “Neko, the animated white lucky cat with small glasses, a green bib and a gold coin” otherwise. The inner WebView is excluded from accessibility traversal to avoid duplicate announcements; the model also has a descriptive alt label.

## Do's and Don'ts

### Do:

- **Do** use the selected left white lucky cat in `image.png` as the mascot authority.
- **Do** preserve the broad smile, pointed pink ears, coral cheeks, viewer-right raised paw, green wave bib, red collar, gold bell and coin.
- **Do** keep glasses discreet and the closed smiling eyes readable.
- **Do** keep native animation subtle and honor reduced motion and accessibility labels.
- **Do** distinguish the current source implementation from user approval of its rendered adaptation.

### Don't:

- **Don't** restore the rejected hybrid or oversized cyan goggles.
- **Don't** replace the native animated GLB with a PNG, picture plane or fallback mascot.
- **Don't** claim the browser preview's existing still demonstrates the updated GLB.
- **Don't** infer new product features or a broader visual identity from this mascot correction.
