# CineVault privacy policy

This policy applies to the official CineVault app. Independently modified or distributed
versions may behave differently.

## Your library and playback

CineVault connects directly to the network shares you configure. Library entries,
watched status, resume positions, and playback usage are stored locally on your Apple TV.
The official commercial version also stores local playback allowance information.
CineVault does not operate a user-account service or send your viewing history or
playback time to developer-operated servers. Local usage is not a cross-device profile.

NAS passwords and the optional TMDB API credential are stored in the device Keychain.
Media is read directly from your NAS for playback, not uploaded to a CineVault server.
Your NAS provider and network administrator may maintain their own connection logs.

## Optional metadata

If you configure a TMDB credential, CineVault sends movie search terms, identifiers,
and that credential to TMDB to obtain metadata. Artwork is downloaded from TMDB.
These requests disclose your network address to those services. TMDB's own
[privacy policy](https://www.themoviedb.org/privacy-policy) governs its processing.
You can use your NAS library without configuring TMDB.

## Purchases

Apple processes App Store purchases, billing, and subscription management. CineVault
uses Apple's StoreKit framework to check purchase entitlements and restore purchases.
CineVault does not receive or store your payment-card details. Apple may retain
purchase history separately from the app's local library and usage data.
See [Apple's privacy policy](https://www.apple.com/legal/privacy/).

## Control and retention

Library sources and individual entries can be managed in the app. Local files and
Keychain items have different system retention rules; removing an app does not
necessarily erase every Keychain item. App Store purchases can be restored using the
Apple Account that owns them. Removing CineVault does not cancel an Apple subscription;
manage subscriptions through your Apple Account settings.

## Questions

Contact the maintainer through the
[CineVault issue tracker](https://github.com/dinesh-devkota/CineVault/issues).
Do not post NAS addresses, passwords, API keys, purchase receipts, or private viewing
history in a public issue.
