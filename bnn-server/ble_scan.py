import asyncio

from bleak import BleakClient, BleakScanner


TARGET_NAME = "B#NN_DEVICE"


async def main() -> None:
    while True:
        print("Scanning...")
        devices = await BleakScanner.discover()

        for d in devices:
            if d.name == TARGET_NAME:
                print(f"Found {d.name}, connecting...")

                async with BleakClient(d.address) as client:
                    if client.is_connected:
                        print("Connected! ✅")

                    # Message send/receive logic will be added later.
                    await asyncio.sleep(10)


if __name__ == "__main__":
    asyncio.run(main())
