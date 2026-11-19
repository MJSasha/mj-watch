# TV action focus — 2026-11-19

Prepared for release 0.9.17 (1143).

The shared TV action now retains its normal fill and foreground when focused, with the theme primary stroke (lime in dark mode, dark green in light mode). A background-coloured inner gap keeps the stroke visible on a lime primary action without increasing bounds or changing spacing. The inset stroke survives viewport clipping. Press, loading and disabled activation guards remain unchanged.

`TvButtonsUiTest` checks the actual screenshot colours of the focus stroke and original fill for primary/secondary actions in both themes, with contrast at least 3:1 against the gap. Existing checks cover stable D-pad press bounds, loading/disabled activation, declared neighbouring gaps and strokes at both ends of a clipped list.

Local runtime checks are deferred to mandatory CI because the local emulator cannot start with available disk space. No physical TV/OEM check was performed. Published artifact and CI verification will be recorded after release.
