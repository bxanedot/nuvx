# Downstream patches

This copy of GLib 0.18.5 is versioned 0.18.6 for Nuvx and carries the upstream fix from [gtk-rs/gtk-rs-core#1343](https://github.com/gtk-rs/gtk-rs-core/pull/1343), backported for the 0.18 series in [Debian's patch](https://sources.debian.org/patches/rust-glib-0.18/0.18.5-7/). The change passes a mutable out-parameter to `g_variant_get_child`, fixing RUSTSEC-2024-0429.

Remove this override when the GTK3/WebKitGTK dependency chain can move to a published GLib version that contains the fix.
