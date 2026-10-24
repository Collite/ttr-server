# SPDX-License-Identifier: Apache-2.0
"""Print only a local conformance ingestion-bot token; never log admin credentials."""
import base64
import os

import requests


def main() -> None:
    url = os.environ.get("OM_URL", f"http://127.0.0.1:{os.environ.get('OM_PORT', '18585')}/api").rstrip("/")
    response = requests.post(
        f"{url}/v1/users/login",
        json={
            "email": "admin@open-metadata.org",
            "password": base64.b64encode(b"admin").decode(),
        },
        timeout=30,
    )
    response.raise_for_status()
    headers = {"Authorization": f"Bearer {response.json()['accessToken']}"}
    bot = requests.get(f"{url}/v1/bots/name/ingestion-bot", headers=headers, timeout=30)
    bot.raise_for_status()
    user = bot.json()["botUser"]["id"]
    auth = requests.get(f"{url}/v1/users/auth-mechanism/{user}", headers=headers, timeout=30)
    auth.raise_for_status()
    print(auth.json()["config"]["JWTToken"])


if __name__ == "__main__":
    main()
