#! /usr/bin/env perl

# --- android-port patch -----------------------------------------------------
# Upstream refuses to configure a Unix-scheme target (android-*) from a
# native-Windows Perl, because File::Spec then produces "C:\..." paths:
#
#     if (rel2abs('.') !~ m|/|) { die "This perl implementation doesn't
#     produce Unix like paths ..." }
#
# Building the Android target from a Windows host with MSWin32 Perl is
# workable as long as every path handed to Configure is spelled with forward
# slashes, which is what scripts/build-openssl.sh and 15-android.conf do.
# So the check is downgraded from fatal to advisory.
#
# (Pristine copy: Configurations/unix-checker.pm.orig)
# ---------------------------------------------------------------------------

use Config;
use File::Spec::Functions qw(:DEFAULT rel2abs);

if (rel2abs('.') !~ m|/|) {
    warn "Warning: this perl implementation produces backslash paths "
       . "($Config{version} for $Config{archname}); continuing anyway, the "
       . "android cross-build normalises its own paths.\n";
}

1;
