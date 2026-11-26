# MJ Watch icon and launch artwork

MJ Watch uses option 02 of the graphite family. The launcher foreground is two overlapping play shapes in emerald and turquoise with a white play triangle and a small MJ signature on a graphite background.

Both Android apps use `ic_launcher` for their adaptive launcher icon. Android applies the launcher mask. On API 33 and later, `ic_launcher_monochrome` supplies the separate thematic silhouette and its play cutout. `ic_notification` remains the separate white small-notification resource. Android TV uses its own 320 × 180 banner with the same new mark.

The system starting window uses the same graphite color and centered lockup as the first Compose frame. In Compose the turquoise and emerald shapes move independently into place, with a soft echo and shifting corner gradients. Once the content has drawn underneath, the startup backdrop fades immediately and the shapes finish as the app becomes visible. With animations disabled, the ready screen is shown immediately.

Review the vector at 32, 48, and 64 px with circular, rounded-square, and light and dark monochrome masks. Verify launcher and startup on actual Android renders as well as running the Android checks.
