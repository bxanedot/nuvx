# Contributing to nuvx

Thanks for helping. By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Issues

- Search open issues first.
- For a bug, include your device and Android version, the nuvx version and the steps to reproduce it. Add logs or a screen recording if you can.
- Before reporting a security problem, read [Security](#security).

## Pull requests

1. Fork the repo and branch from `main`.
2. Keep each PR to one change. Open an issue first to discuss anything large.
3. Build what you touched, and run the tests for it:
   - Kotlin: `./gradlew test`
   - Web: see [app/web/README.md](app/web/README.md#checks)
   - Worker: `npm test` in `core/worker`
4. Follow the style of the code around your change. Kotlin uses the official code style.
5. Say what changed and why. Add screenshots for UI changes.

Never commit secrets, keystores, `local.properties` or `keystore.properties`.

## License

nuvx is under [GPL-3.0](LICENSE). Contributions are licensed the same way.

## Security

Don't open a public issue for a vulnerability. Email bxanee@gmail.com instead.
