#!/usr/bin/env python3
"""Patch SillyTavern extensions install to clean up half-installed folders.

Applies to the staged bundle copy (does not modify the git submodule).
Addresses leftover empty/partial dirs that block reinstall ("Directory already exists").
"""
from __future__ import annotations

import pathlib
import sys

MARKER = "TAVERN_POCKET_EXTENSION_CLEANUP"

PATCHED_INSTALL_SNIPPET = '''
        const extensionPath = path.join(basePath, extensionNameSanitized);
        const folderName = path.basename(extensionPath);

        // TAVERN_POCKET_EXTENSION_CLEANUP
        // Remove incomplete leftovers (no manifest) so a failed install can be retried.
        if (fs.existsSync(extensionPath)) {
            const manifestPath = path.join(extensionPath, 'manifest.json');
            if (!fs.existsSync(manifestPath)) {
                console.warn(`Removing incomplete extension folder at ${extensionPath}`);
                await fs.promises.rm(extensionPath, { recursive: true, force: true });
            }
        }

        if (fs.existsSync(extensionPath)) {
            return response.status(409).send(`Directory already exists at ${extensionPath}`);
        }

        const cloneOptions = { depth: 1 };
        if (branch) {
            cloneOptions.branch = branch;
        }
        try {
            await git.clone(parsedUrl.href, extensionPath, cloneOptions);
            console.info(`Extension has been cloned to ${extensionPath} from ${parsedUrl.href} at ${branch || '(default)'} branch`);

            const manifest = await getManifest(extensionPath);
            if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
                throw new Error('Manifest is not a valid JSON object.');
            }
            const { version, author, display_name } = manifest;
            return response.send({ version, author, display_name, extensionPath, folderName });
        } catch (installError) {
            if (fs.existsSync(extensionPath) && !fs.existsSync(path.join(extensionPath, 'manifest.json'))) {
                await fs.promises.rm(extensionPath, { recursive: true, force: true });
                console.warn(`Cleaned incomplete extension folder after failed install: ${extensionPath}`);
            }
            throw installError;
        }
'''

OLD_BLOCK_START = "        const extensionPath = path.join(basePath, extensionNameSanitized);"
OLD_BLOCK_END = "    } catch (error) {\n        console.error('Importing extension failed', error);"


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <path-to-extensions.js>", file=sys.stderr)
        return 2
    path = pathlib.Path(sys.argv[1])
    text = path.read_text(encoding="utf-8")
    if MARKER in text:
        print(f"Already patched: {path}")
        return 0

    start = text.find(OLD_BLOCK_START)
    if start < 0:
        print("Could not find extensionPath block", file=sys.stderr)
        return 1
    end = text.find(OLD_BLOCK_END, start)
    if end < 0:
        print("Could not find install catch block", file=sys.stderr)
        return 1

    # Replace from extensionPath through the end of the try body (before outer catch)
    new_text = text[:start] + PATCHED_INSTALL_SNIPPET.strip("\n") + "\n" + text[end:]
    path.write_text(new_text, encoding="utf-8")
    print(f"Patched extension install cleanup: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
