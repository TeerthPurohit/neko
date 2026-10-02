# Firebase setup

Firebase provides Android push messaging and Hosting for the static browser preview. The durable agent and model gateway continue to run on the existing backend; Hosting is not an Android application runtime.

## Android

Register the package `dev.neko.app` in your Firebase project and download its configuration to `app/google-services.json` using the Firebase CLI or Firebase MCP. This file is ignored by Git. The Google services plugin is applied only when the file exists, so public source builds can still run without a project-specific configuration.

Firebase's default initialization supplies the client configuration automatically in configured APKs. Existing manual configuration import remains available. The application requests a messaging token and schedules backend synchronization; notifications require a signed-in app account, device token synchronization, Android notification permission, and enabled FCM API/service-account permissions.

## Backend secrets

Local credentials may be named `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, and `FIREBASE_PRIVATE_KEY`. Deploy them to the backend secret store as `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, and `FCM_PRIVATE_KEY`. Never include the service-account private key in Android resources, Hosting assets, source commits, or logs. The optional private-key ID is not needed by the backend.

## Hosting

`firebase.json` deploys only `.tools/firebase-hosting`, containing the sanitized static preview. Prepare that directory from the public preview snapshot before deploying. Set the Hosting site and `.firebaserc` project to your own Firebase project. Do not point Hosting at the repository root. The preview uses sample data and does not provide the Android app's authenticated agent functionality.

Deploy only Hosting with the Firebase MCP `firebase_deploy` tool or `npx.cmd -y firebase-tools@latest deploy --only hosting`. For MCP background deployment, keep the same server connection open while polling `firebase_deploy_status`: job IDs belong to that server process.
