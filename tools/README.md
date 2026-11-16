# tools/

Helper scripts for this repo. None of them are shipped in the APK.

- `check_braces.py`   — static brace-balance check over every Kotlin file.
- `cf-tunnel.sh`      — expose the local tool API over a Cloudflare Tunnel from
                       Termux / any Linux on the device (needs `cloudflared`).
- `ascii_grid_test.py`— mirrors `draw_sprite_from_ascii`'s newline normalisation
                       and grid validation, so those rules can be checked
                       without a device. Run: `python3 tools/ascii_grid_test.py`
- `ascii_symbols_test.py` — mirrors `get_canvas_ascii`'s colour→symbol assignment,
                       proving the map is stable across frames and safe on a
                       saturated palette. Run: `python3 tools/ascii_symbols_test.py`
