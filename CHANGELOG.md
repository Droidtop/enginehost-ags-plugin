# Changelog

All notable changes to this plugin's wrapper/plugin work.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to it for its wrapper releases.


## Added

- Enginehost wrapper for the AGS Android runtime: runtime contract, build/sign/publish workflow, bundle metadata, certified key.
- Declare plugin version 0.9.0 for the testing channel.

## Changed

- Sign job: pin Enginehost's signing tooling to the build-counter version so reruns cannot repeat a version.
- Seams for Enginehost: save folder mapped to system saved-games folder, both ABIs (arm64-v8a and x86_64), resource table at package id 0x80, no minification, plugin's own application id.
