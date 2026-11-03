# canvas

Community canvases for nuvx: short looping videos that play over a song's cover in the player, one file per song named
`<YouTube Music video id>.mp4`.

Upload at **[canvas.nuvx.lol](https://canvas.nuvx.lol)**: paste the song's YouTube Music link, drop or choose the MP4 (up to 10 MB,
a few seconds that loop, silent) and enter the upload passcode. The upload page (`index.html`) and these videos are published to
canvas.nuvx.lol by `.github/workflows/canvas-pages.yml`; the app loads `https://canvas.nuvx.lol/<id>.mp4`.

Only upload videos you have the right to share. nuvx skips canvases when the device asks for reduced motion.
