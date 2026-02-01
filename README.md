# Hubitat Flic Integration (Buttons and Twist)
Full two way integration between Hubitat and Flic Hub - For Flic Buttons and Flic Twist

## Pre-requisites:
Flic Hub and Hubitat Hub must be on the same LAN

## Installation Instructions

## Flic Hub Studio

1) In the Flic Mobile app, select your Flic Hub then go to settings and turn on "Enable SDK Access"
2) Check for new Firmware and update if needed
3) In a browser on your local network, go to https://studio.flic.io/
4) Enter the IP address of your Flic Hub
5) Enter the password for your Flic Hub and click connect (password can be changed in the mobile app settings if required)
6) Click "+ Create Module"
7) Give the new module a name i.e. HE Integration
8) Copy and Paste main.js from this repository into main.js on your Flic Hub
9) Click the Play button next to your module name

## Hubitat
1) Add Flic_Integration_TCP_Driver.groovy to the Drivers Code section of your Hubitat interface
2) Add Flic_Integration.groovy to the Apps Code section of your Hubitat interface
3) Go to the Apps page
4) Click "+ Add User App"
5) Select "Flic Integration V1.0"
6) Enter the IP address of your Flic Hub
7) Keep the defult port - 46321
8) Click "Configure Flic Devices"
9) The app will create a TCP Child Device that communicates wth your Flic Hub, and will scan your Flic Hub for available Flic devices
10) Click Next
11) Select which device you want to configure and Click "Configure selected device"
12) Work your way through the page, creating virtual devices or selecting Hubitat devices to control
13) At the bottom of the configuration page, review the Instructions for the "Flic Mobile App Setup" (detailed below)
14) Click Save / Configure another Flic device
15) Once you have finished configuring your Flic devices, click "Push Configurtion to Flic hub" on the main page
16) Click Done


## Flic Mobile App (For Flic Twists only, button devices do not need any further configuration)
1) Reference the instructions at the bottom of each configuration page in the Hubitat app
3) Master Twist Event:
       * Click "+" and select the function required (as per your matchin choice in Hubitat)
           * Brightness, CT, Color or Saturation > Flic Hub Studio > Click the 3 dots > add device with exact name specified in the Hubitat app i.e. "flic1-dimmer0" with type "Ligt"
           * Volume > Flic Hub Studio > Click the 3 dots > add device with exact name specified in the Hubitat app i.e. "flic1-speaker0" with type "Speaker"
           * Blinds Level > Flic Hub Studio > Click the 3 dots > add device with exact name specified in the Hubitat app i.e. "flic1-blinds0" with type "Blinds"
           * Do not select Scene Blender or Advanced Dimming
4) Master Push / Double Push Events:
       * Click "+"
       * Scroll down and expand "Advanced"
       * Select Flic Hub Studio
       * Enter the action message, as specified in the Hubitat app i.e "flic1-button0-event1" for Push or "flic1-button0-event2" for double push
5) If you are using the Push & Select Mode, the process is the same as above, using the various naming conventions specified in the Hubitat app:

Your Flic Twists will now operate as desired - Communication goes both ways, so the virtual devices in the Flic app stay in sync with your devices in Hubitat

Enjoy
