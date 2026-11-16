# MJWatch family icon

The MJ path, coordinates, 4.5-unit stroke and rounded caps/joins are copied
unchanged from MJReader/design-system/mj-family/reader-icon.svg.
Background: #141925; foreground: #C7FF68. The UI background has a separate role.
Watch uses a film frame/strip in the x=42–66, y=66–80 symbol slot.

Shared resources live in design-system/src/main/res. Both applications use
ic_launcher for icon and roundIcon; Android supplies the mask. API 33 adds
the transparent foreground as monochrome. ic_notification is a separate
white film silhouette, available for notification small icons. The app does
not currently post download notifications. banner is a separate 320×180 TV
resource, not a launcher icon. No bitmap renderer or font dependency is used.

Review the exact vector paths at 32, 48 and 64 px with circle, rounded-square,
and light/dark monochrome masks. Generated previews belong in ignored dist/.
Run unit tests, lintDebug and assembleDebug for both applications; emulator
checks should state which devices and screens were actually exercised.
