# Mechanics preview under fitness5

The 32-level mechanics preview (seed 1, 120 tries, x2 on tier >= 2) re-selected by fitness5 (no queue repair), next to the shipped preview. Long chain (x16), order dependence over 64 draws at 200000 nodes, solver line. Hidden-colour containers are treated as visible: on `hidden` levels the fair numbers are upper bounds. Commit 8ce5435-dirty, wall time 996 s on 7 threads.

| L | recipe | tier | belt | unfair | traps | fair share | blind bits | hidden | fair hidden | winnable uniform start / 1/3 | winnable 1/3, H=40 | w>=2, mean w | floors (old) | new: unfair / traps / blind / fair hidden / winnable 1/3 H=40 / w>=2, mean w | new floors | f5 new |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | intro: frozen | 0 | 10 | 0 | 0 | 100% | 0.0 | 2.0 | 2.0 | 100% / 100% | 100% | 84%, 2.05 | fairhidden 2.0 | 0 / 0 / 0.0 / 0.0 / 100% / 100%, 2.46 | fairhidden 0.0 | 15.9 |
| 2 | frozen x2 + juggle + left-slot steal | 0 | 10 | 1 | 0 | 90% | 3.2 | 9.0 | 7.0 | 39% / 81% | 91% | 72%, 2.50 | unfair 1.0,blindratio 0.35 | 0 / 0 / 2.0 / 5.8 / 48% / 79%, 2.00 | blindratio 0.35 | 26.2 |
| 3 | frozen x3 + juggle + steal + keystone | 1 | 10 | 3 | 2 | 90% | 8.6 | 16.0 | 13.7 | 0% / 0% | 0% | 72%, 3.00 | unfair 3.0,deeptraps 2.0,blindratio 0.54,order 0.00 | 0 / 0 / 1.8 / 10.1 / 41% / 74%, 2.45 | blindratio 0.30 | 26.8 |
| 4 | frozen x4 + juggle, squatter dig + steal | 2 | 16 | 0 | 0 | 100% | 4.5 | 9.0 | 9.0 | 0% / 41% | 80% | 92%, 3.64 | blindratio 0.50,fairhidden 9.0 | 0 / 0 / 2.2 / 23.0 / 0% / 63%, 2.58 | order 0.02 | 33.0 |
| 5 | intro: tied | 0 | 10 | 0 | 0 | 100% | 1.0 | 2.0 | 2.0 | 94% / 100% | 100% | 83%, 1.83 | blindratio 0.50,fairhidden 2.0 | 0 / 0 / 0.0 / 0.0 / 100% / 100%, 2.53 | fairhidden 0.3 | 16.0 |
| 6 | tied x2 + juggle + left-slot steal | 0 | 10 | 0 | 0 | 100% | 2.0 | 6.2 | 6.2 | 94% / 95% | 69% | 66%, 1.93 | blindratio 0.32 | 0 / 0 / 0.6 / 7.3 / 47% / 66%, 2.25 | pass | 29.5 |
| 7 | tied x3 + juggle + steal + keystone | 1 | 10 | 1 | 0 | 88% | 5.0 | 8.0 | 7.0 | 0% / 0% | 0% | 68%, 2.08 | unfair 1.0,blindratio 0.63,fairhidden 7.0,order 0.00 | 0 / 0 / 5.0 / 9.6 / 98% / 85%, 3.34 | blindratio 0.51 | 26.0 |
| 8 | tied x3 + juggle, squatter dig + steal | 2 | 16 | 2 | 2 | 88% | 16.5 | 38.8 | 28.9 | 0% / 0% | 2% | 74%, 4.08 | unfair 2.0,deeptraps 3.0,blindratio 0.43,order 0.02 | 0 / 0 / 3.0 / 17.6 / 0% / 57%, 1.86 | order 0.00 | 36.3 |
| 9 | intro: hidden | 0 | 10 | 0 | 0 | 100% | 0.0 | 2.0 | 2.0 | 100% / 100% | 100% | 76%, 2.10 | fairhidden 2.0 | 0 / 0 / 0.0 / 3.2 / 100% / 86%, 3.90 | fairhidden 2.9 | 15.7 |
| 10 | hidden x3 + juggle + left-slot steal | 0 | 10 | 1 | 0 | 94% | 10.1 | 14.6 | 14.1 | 84% / 75% | 45% | 61%, 2.33 | unfair 1.0,blindratio 0.69 | 0 / 0 / 2.3 / 5.3 / 100% / 83%, 3.69 | blindratio 0.31 | 23.2 |
| 11 | hidden x4 + juggle + steal + keystone | 1 | 10 | 4 | 4 | 64% | 8.5 | 11.7 | 8.5 | 92% / 100% | 98% | 91%, 4.33 | unfair 4.0,deeptraps 8.0,blindratio 0.73 | 1 / 0 / 1.0 / 6.9 / 53% / 74%, 2.85 | unfair 1.5 | 25.6 |
| 12 | hidden x5 + juggle, squatter dig + steal | 2 | 16 | 4 | 4 | 69% | 5.0 | 18.7 | 15.2 | 0% / 19% | 81% | 82%, 4.66 | unfair 4.0,deeptraps 4.0 | 0 / 0 / 3.4 / 26.6 / 6% / 64%, 2.82 | order 0.05 | 35.5 |
| 13 | intro: wide | 0 | 10 | 0 | 0 | 100% | 0.0 | 1.0 | 1.0 | 100% / 100% | 100% | 88%, 3.12 | fairhidden 1.0 | 0 / 0 / 0.0 / 6.5 / 100% / 79%, 2.05 | pass | 20.0 |
| 14 | wide x2 + juggle + left-slot steal | 0 | 10 | 0 | 0 | 100% | 1.4 | 11.7 | 11.7 | 2% / 86% | 31% | 78%, 3.14 | pass | 0 / 0 / 1.4 / 11.7 / 31% / 78%, 3.14 | pass | 25.2 |
| 15 | wide x2 + juggle + steal + keystone | 1 | 10 | 1 | 1 | 83% | 4.0 | 6.6 | 5.6 | 0% / 5% | 5% | 81%, 2.50 | unfair 1.0,deeptraps 1.0,blindratio 0.61,fairhidden 5.6,order 0.05 | 0 / 0 / 1.6 / 14.4 / 98% / 74%, 4.68 | unfair 1.0 | 23.6 |
| 16 | wide x3 + juggle, squatter dig + steal | 2 | 16 | 3 | 0 | 88% | 3.0 | 23.2 | 19.2 | 0% / 0% | 0% | 79%, 2.88 | unfair 3.0,order 0.00 | 1 / 0 / 0.7 / 20.3 / 59% / 71%, 2.82 | pass | 37.5 |
| 17 | intro: colour_bench | 0 | 10 | 0 | 0 | 100% | 0.0 | 0.0 | 0.0 | 100% / 100% | 100% | 100%, 2.22 | fairhidden 0.0 | 0 / 0 / 0.0 / 0.0 / 100% / 94%, 2.50 | fairhidden 0.8 | 16.1 |
| 18 | colour_bench x1 + juggle + left-slot steal | 0 | 10 | 0 | 0 | 100% | 4.8 | 11.9 | 11.9 | 0% / 53% | 5% | 64%, 1.96 | blindratio 0.40,order 0.05 | 0 / 0 / 1.6 / 6.9 / 42% / 68%, 1.94 | pass | 29.3 |
| 19 | colour_bench x1 + juggle + steal + keystone | 1 | 10 | 5 | 5 | 79% | 13.0 | 25.0 | 14.6 | 100% / 100% | 92% | 76%, 5.49 | unfair 5.0,deeptraps 12.0,blindratio 0.52 | 0 / 0 / 3.5 / 16.5 / 100% / 72%, 3.64 | unfair 1.0 | 42.1 |
| 20 | colour_bench x1 + juggle, squatter dig + steal | 2 | 16 | 2 | 1 | 88% | 10.5 | 17.2 | 13.7 | 20% / 94% | 45% | 89%, 6.75 | unfair 2.0,deeptraps 1.0,blindratio 0.61,fairhidden 13.7,meanw 6.8 | 0 / 0 / 1.0 / 11.0 / 100% / 87%, 5.49 | pass | 46.9 |
| 21 | intro: turntable | 0 | 10 | 0 | 0 | 100% | 0.0 | 2.2 | 2.2 | 100% / 100% | 100% | 90%, 2.40 | fairhidden 2.2 | 0 / 0 / 0.0 / 4.2 / 100% / 90%, 3.24 | pass | 14.8 |
| 22 | turntable x2 + juggle + left-slot steal | 0 | 10 | 0 | 0 | 100% | 2.9 | 19.5 | 19.5 | 8% / 100% | 97% | 72%, 2.81 | pass | 0 / 0 / 2.9 / 19.5 / 97% / 72%, 2.81 | pass | 24.3 |
| 23 | turntable x3 + juggle + steal + keystone | 1 | 10 | 1 | 1 | 93% | 6.2 | 17.4 | 16.4 | 31% / 100% | 88% | 83%, 4.32 | unfair 1.0,deeptraps 1.0,blindratio 0.36 | 0 / 0 / 1.4 / 14.7 / 42% / 74%, 3.09 | pass | 21.8 |
| 24 | turntable x3 + juggle, squatter dig + steal | 2 | 16 | 1 | 0 | 95% | 2.1 | 18.1 | 17.8 | 0% / 100% | 100% | 76%, 3.10 | pass | 1 / 0 / 3.0 / 22.5 / 0% / 61%, 2.58 | order 0.00 | 32.3 |
| 25 | intro: gate | 0 | 10 | 0 | 0 | 100% | 0.0 | 1.0 | 1.0 | 0% / 100% | 100% | 83%, 2.25 | fairhidden 1.0 | 0 / 0 / 0.0 / 0.0 / 100% / 90%, 2.95 | fairhidden 1.6 | 15.5 |
| 26 | gate x2 + juggle + left-slot steal | 0 | 10 | 0 | 0 | 100% | 2.9 | 13.6 | 13.6 | 100% / 78% | 72% | 74%, 3.26 | pass | 0 / 0 / 2.2 / 10.5 / 38% / 78%, 3.00 | pass | 18.8 |
| 27 | gate x3 + juggle + steal + keystone | 1 | 10 | 0 | 0 | 100% | 0.4 | 6.1 | 6.1 | 92% / 100% | 100% | 93%, 3.93 | fairhidden 6.1 | 0 / 0 / 2.3 / 10.7 / 45% / 77%, 4.19 | pass | 24.3 |
| 28 | gate x3 + juggle, squatter dig + steal | 2 | 16 | 1 | 0 | 95% | 4.6 | 18.1 | 17.7 | 0% / 0% | 0% | 63%, 2.54 | order 0.00 | 0 / 0 / 0.0 / 17.0 / 61% / 89%, 4.69 | pass | 42.6 |
| 29 | frozen + tied + juggle + steal + keystone | 1 | 10 | 1 | 0 | 92% | 3.2 | 14.3 | 13.3 | 17% / 19% | 6% | 49%, 2.09 | unfair 1.0,multi 0.49,order 0.06 | 0 / 0 / 3.0 / 15.2 / 0% / 55%, 2.36 | order 0.00 | 23.8 |
| 30 | hidden + gate + juggle, gutter halves + heralded climax | 2 | 10 | 0 | 0 | 100% | 5.8 | 23.8 | 23.8 | 30% / 5% | 0% | 84%, 4.38 | order 0.00 | 0 / 0 / 4.4 / 17.0 / 89% / 86%, 3.29 | pass | 43.6 |
| 31 | wide + colour_bench + juggle + steal + keystone | 1 | 10 | 4 | 0 | 76% | 11.6 | 15.4 | 14.2 | 0% / 50% | 16% | 76%, 4.17 | unfair 4.0,blindratio 0.75,order 0.16 | 3 / 0 / 4.4 / 21.9 / 75% / 83%, 3.81 | unfair 2.5 | 45.1 |
| 32 | turntable + gate + juggle + left-slot steal | 0 | 10 | 0 | 0 | 100% | 14.7 | 22.3 | 22.3 | 100% / 100% | 75% | 81%, 3.56 | blindratio 0.66 | 0 / 0 / 14.7 / 22.3 / 75% / 81%, 3.56 | blindratio 0.59 | 21.2 |

| metric (mean, solver line) | shipped preview | fitness5 |
|---|---|---|
| levels with >= 1 unfair decision | 16 / 32 | 4 / 32 |
| unfair decisions (sum) | 35 | 6 |
| traps (sum) | 20 | 0 |
| fair share | 93% | 99% |
| blind bits | 4.9 | 2.2 |
| hidden bits | 12.7 | 12.0 |
| fair hidden bits | 11.3 | 11.8 |
| winnable from start, uniform | 44% | 44% |
| winnable at 1/3, H=40 | 59% | 64% |
| choices with w >= 2 | 78% | 78% |
| mean w | 3.17 | 3.08 |
| floors passed | 4 | 13 |
