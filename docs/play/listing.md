# Google Play listing

Text and answers for the Play Console, kept here so they change along with the app.

## App details

- **App name** (30 max): `Layerwatch for Bambu Lab` (24)
- **Category:** Tools
- **Tags:** 3D printing, remote monitoring
- **Contact email:** d01.devel@gmail.com
- **Website:** https://d01.github.io/layerwatch/
- **Privacy policy:** https://d01.github.io/layerwatch/privacy.html

### Short description (80 max)

```
Watch and control your Bambu Lab 3D printer over your own network. No cloud.
```

### Full description (4000 max)

```
Layerwatch keeps an eye on your Bambu Lab 3D printer from your Android phone, talking to it directly over your own network. No cloud account, no ads, no tracking, and it's free and open source.

LIVE CAMERA
Watch the chamber camera live. Layerwatch adapts its buffer to the connection, so video stays smooth even when the printer's WiFi is weak.

EVERYTHING THE PRINTER REPORTS
• Progress, layer, time left and a finish-time forecast
• Nozzle, bed and chamber temperatures, and fans
• AMS spools with colours, remaining filament, humidity and drying status
• Printer alerts explained in plain English, not just error codes

ALERTS, EVEN WITH THE PHONE LOCKED
While a print runs, Layerwatch keeps watching after you close it, with a Live Update progress notification on Android 16 and later. You're alerted when the print finishes, fails, pauses or runs out of filament, with a camera snapshot. Watching stops when the print does, so it doesn't drain your battery while the printer is idle.

PRINT DETAILS
• A 3D view of the toolpaths, cut away at the current layer
• A layer-by-layer view, and which object is which
• What's coming up: bridges, top surfaces, the final layers
• Whether the loaded spools have enough filament for the rest of the job
• The slicer's settings and estimates

CONTROLS WITH SAFETY CATCHES
Turn the chamber light on and off. Pause, stop and speed changes need a deliberate press-and-hold, so a stray tap never ruins a print.

HISTORY AND TIMELAPSES
Look back at finished prints. Download and play timelapses; downloads carry on in the background.

MADE FOR EVERY SCREEN
Layouts for phones, foldables (folded, unfolded and tabletop) and tablets, plus a home-screen widget.

TRY IT WITHOUT A PRINTER
Demo mode runs a simulated print, so you can explore every screen first.

PRIVATE BY DESIGN
Layerwatch connects only to your printer. It has no servers, analytics or ads, and your access code is encrypted on the device. Use it at home, or from anywhere through a VPN into your home network.

PRINTERS
Developed on the X1 Carbon. Also supports the X1, X1E, P1P, P1S, P2S, A1, A1 mini, A2L, H2D, H2D Pro, H2S, H2C and X2D. Reports from owners of those models are welcome on GitHub.

SETUP
On the printer, turn on LAN Only Mode and Developer Mode (and LAN Only Liveview for the camera on X1, P2S and H2 printers), then enter its IP address and access code. Note that LAN Only Mode disconnects the printer from Bambu's cloud and the Bambu Handy app.

OPEN SOURCE
Licensed under GPL-3.0: https://github.com/d01/layerwatch

Layerwatch is an independent project, not affiliated with or endorsed by Bambu Lab.
```

## Graphics

| Asset | File |
|---|---|
| App icon, 512 × 512 | `icon-512.png` |
| Feature graphic, 1024 × 500 | `feature-graphic.png` |
| Phone screenshots | `screenshots/phone-*.png` |
| Tablet screenshots (7" and 10") | `screenshots/tablet-*.png` |

AI asset declaration: the icon and feature graphic are labeled "created or edited using AI";
the screenshots are not.

## App content

- **Ads:** No, the app contains no ads.
- **Sign in details** (was "App access"): Yes, restricted, with one entry, "Demo mode (no
  printer needed)", no username or password, and these instructions:
  *"No account or sign-in exists. The app connects to the user's own Bambu Lab 3D printer
  on their local network, using the printer's IP and the access code shown on its screen.
  Reviewers without a printer: on the first screen, tap 'Try the demo'. A simulated printer
  runs a short sample print, and every screen, control, notification and alert works with
  it."*
- **Advertising ID:** No.
- **Content rating:** IARC category "All Other App Types", No to every question. Result:
  ESRB Everyone, PEGI 3, ClassInd All ages.
- **Target audience:** 18 and over. Not designed for children.
- **News app:** No.
- **Government app:** No.
- **Financial features:** None.
- **Health:** No.
- **Data safety:** see below.

### Data safety

- **Does your app collect or share any of the required user data types?** No.

  The app transmits data only to the user's own printer on their network, never to the
  developer or any third party. Google's definition of "collection" covers data
  transmitted off the device to the developer or third parties, so nothing is collected.
  The access code, history and cached files stay on the device.
- **Is all of the user data collected by your app encrypted in transit?** Not asked when
  nothing is collected. If it is, answer Yes: every printer connection is TLS.
- **Do you provide a way for users to request that their data is deleted?** Not asked
  when nothing is collected. In-app, "Forget printer" removes the stored credentials, and
  uninstalling removes everything.

### Foreground service declaration

- **Type:** `connectedDevice` (FOREGROUND_SERVICE_CONNECTED_DEVICE)
- **Task:** Other → "Monitoring a 3D printer"
- **Description:**

  *"While a 3D print is in progress, Layerwatch keeps a connection to the user's 3D
  printer on their local network, to show live progress in an ongoing notification and to
  alert them immediately if the print fails, pauses or runs out of filament. The printer
  is an external device the app communicates with over the network. The service starts
  only when the user opens the app while a print is in progress. It stops itself when the
  print finishes, or when the user turns background watching off. It never runs while the
  printer is idle, and never starts at boot. Deferring or interrupting it would mean
  missing a failed print, which wastes material and can be a safety concern."*
- **User impact if deferred:** alerts about failed or stalled prints would arrive late or
  not at all.
- **Video:** a short screen recording of the demo showing the ongoing notification and
  an alert after leaving the app. Record it on the phone.

### User-initiated data transfer jobs

Only needed if the Console asks: timelapse downloads that the user starts with a tap,
from the printer to the phone.

## Release notes (0.1.0)

```
First release: live camera, status, AMS and drying, alerts with the phone locked, print details with a 3D view, history and timelapses, and a demo mode.
```
