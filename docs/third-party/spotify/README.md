# Spotify icon

Spotify and the Spotify logo are trademarks of Spotify AB. Fortia is not affiliated with Spotify.

The files in this folder are unaltered copies from Spotify's official icon download:

- Page: https://developer.spotify.com/documentation/design (Spotify for Developers → Design &
  Branding Guidelines → "Download Spotify icon")
- File: https://developer.spotify.com/images/guidelines/design/2024-spotify-logo-icon.zip
  (downloaded 2026-10-26, 3 870 806 bytes, SHA-256
  `7e1983c32004db74a8e4a5c705e1bffcf11b829f6da74c52d678621238e2846d`)

The zip has no license or readme file; the guidelines on the page above are the terms of use. Only the
RGB (screen) variants are kept here; the print files of the zip (CMYK and Pantone SVG, EPS, PDF, AI) are
not. SHA-256 of the copies:

| File | SHA-256 |
|---|---|
| Primary_Logo_Black_RGB.svg | `5595afea0e6f009b1dd8529511204d0fd5ca035e49c85409d1697063b3c27a05` |
| Primary_Logo_White_RGB.svg | `8929d148f54cede78f0f36ce90df815e5ea5e5559e7faeccad3669302ef2daa1` |
| Primary_Logo_Green_RGB.svg | `ead72f82725038389cca09f439fdb7807640e122500c934f2500c7036bf40dbb` |
| Spotify_Primary_Logo_RGB_Black.png | `14113bb619ec259ae51a713e4098038c2a51bc2e65ed5dd97da25aa72702d7a5` |
| Spotify_Primary_Logo_RGB_White.png | `710e1b3e1525a0d36fc7cef4ad96fe0c16ea0b819e5b02183128aa7b6c176630` |
| Spotify_Primary_Logo_RGB_Green.png | `193ef6f3d236da9a1e1e2991bf44a75a082fb3c99255c9eec9b47ea5f66413f1` |

## In the app

`app/src/main/res/drawable/ic_spotify_black.xml` and `ic_spotify_white.xml` are the black and white
SVGs as VectorDrawables: the same viewport and path data, the official fill (#000000, #FFFFFF), nothing
else. They sit next to the "Spotify playlists" attribution of the "Music for today" cards and sheet
(`ui/training/music/MusicCards.kt`), following the guidelines:

- **Colour:** the green icon only goes on black or white backgrounds; on any other background the
  monochrome one: black on light, white on dark. The cards and the sheet use Material You surface colours
  (never pure white or black), so the app shows the black icon in the light theme and the white one in
  the dark theme. The green files are kept for reference only.
- **Size:** at least 21 px digital: the circle is drawn 21.4 dp across (the drawable is 22 dp high,
  the viewport has a small margin).
- **Clear space:** half the icon's height (about 11 dp) on every side: 12 dp to the text and to the
  content around it.
- Never rotated, recoloured, stretched, cropped or placed over cover art.
