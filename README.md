# Hubitat Flic Integration (Buttons and Twist)

Full two-way integration between **Hubitat** and **Flic Hub**, supporting **Flic Buttons** and **Flic Twist** devices.

This integration allows:
- Flic devices to control Hubitat devices
- Hubitat devices to stay in sync with Flic virtual devices
- Real or virtual devices on either side

---

## Pre-requisites
- Hubitat hub and Flic Hub must be on the same local network (LAN)
- Flic Hub firmware must support SDK / Studio access

---

## Installation Instructions

---

## Flic Hub Studio

1. In the **Flic Mobile App**, select your **Flic Hub**
2. Go to **Settings** and enable **“Enable SDK Access”**
3. Check for new firmware and update if required
4. In a browser on your local network, go to:  
   **https://studio.flic.io/**
5. Enter the IP address of your Flic Hub
6. Enter the Flic Hub password (managed in the mobile app) and click **Connect**
7. Click **“+ Create Module”**
8. Give the module a name (e.g. `HE Integration`)
9. Copy and paste `main.js` from this repository into the Studio editor (replace all contents)
10. Click the **Play ▶️** button next to your module name

> Only **one** Studio module is required.

---

## Hubitat

1. Add `Flic_Integration_TCP_Driver.groovy` to **Drivers Code**
2. Add `Flic_Integration.groovy` to **Apps Code**
3. **Ensure OAuth is enabled** for the app code
4. Go to **Apps**
5. Click **“+ Add User App”**
6. Select **“Flic Integration v1.0”**
7. Enter the IP address of your Flic Hub
8. Keep the default port: **46321**
9. Click **“Configure Flic Devices”**

The app will:
- Create a TCP child device automatically
- Scan your Flic Hub for available Flic devices

10. Click **Next**
11. Select a device and click **“Configure selected device”**
12. Work through the configuration page:
    - Create virtual devices **or**
    - Select real Hubitat devices to control
13. At the bottom of the page, review the **“Flic Mobile App Setup”** instructions
14. Click **Save / Configure another device**
15. When finished, return to the main page and click **“Push Configuration to Flic Hub”**
16. Click **Done**

> Only **one instance** of this app should be installed.

---

## Flic Mobile App  
*(Flic Twist only — Button devices do not require additional setup)*

1. Follow the **“Flic Mobile App Setup”** instructions shown at the bottom of each device configuration page in Hubitat

2. **Master Twist Event**
   - Click **“+”** and select the required function (matching your Hubitat selection):
     - **Brightness / CT / Color / Saturation**  
       → Flic Hub Studio → Add device  
       → Exact name from Hubitat (e.g. `flic1-dimmer0`)  
       → Device type: **Light**
     - **Volume**  
       → Device name: `flic1-speaker0`  
       → Device type: **Speaker**
     - **Blinds Level**  
       → Device name: `flic1-blinds0`  
       → Device type: **Blinds**
   - Do **not** select *Scene Blender* or *Advanced Dimming*

3. **Master Push / Double Push Events**
   - Click **“+”**
   - Scroll down and expand **Advanced**
   - Select **Flic Hub Studio**
   - Enter the action message exactly as shown in Hubitat:
     - Push: `flic1-button0-event1`
     - Double Push: `flic1-button0-event2`

4. **Push & Twist / Selector Modes**
   - Configure in the same way, using the naming conventions shown in the Hubitat app for each selector position

---

Your Flic devices will now operate as configured.

Communication is fully **two-way**, so changes made in Hubitat are reflected in Flic virtual devices and vice versa.

**Enjoy 🎉**
