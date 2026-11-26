# MJ Watch icon and launch artwork

MJ Watch uses option 02 of the graphite family. The launcher foreground is two overlapping play shapes in emerald and turquoise with a white play triangle and a small MJ signature on a graphite background.

Both Android apps use `ic_launcher` for their adaptive launcher icon. Android applies the launcher mask. On API 33 and later, `ic_launcher_monochrome` supplies the separate thematic silhouette and its play cutout. `ic_notification` remains the separate white small-notification resource. Android TV uses its own 320 × 180 banner with the same new mark.

The Android starting window shares its graphite color and centered MJ Watch lockup with the Compose loading view. The loading view adds two soft corner gradients and assembles the play mark while the app initializes. The motion follows the system animation scale. Content appears as soon as initialization finishes.

Review the vector at 32, 48, and 64 px with circular, rounded-square, and light and dark monochrome masks. Verify launcher and startup on actual Android renders as well as running the Android checks.
