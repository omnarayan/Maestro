#!/bin/bash
# upgrade-maestro-ports.sh
# Adds --driver-host-port support to existing Maestro installation
# Automatically backs up original JARs for easy rollback

set -e

BACKUP_DIR="$HOME/.maestro-backups/backup"
GITHUB_REPO="omnarayan/Maestro"

echo "🔧 Maestro Multi-Device Port Support Upgrade"
echo "============================================="
echo ""
echo "ℹ️  ABOUT THIS UPGRADE"
echo ""
echo "This is an unofficial stopgap solution that adds --driver-host-port"
echo "support to enable concurrent testing on multiple devices."
echo ""
echo "A PR has been submitted to the official Maestro repository."
echo "Once merged, this upgrade script will no longer be needed, and"
echo "you can simply update to the official Maestro release."
echo ""
echo "GitHub PR: https://github.com/mobile-dev-inc/maestro/pull/2821"
echo ""
echo "⚠️  DISCLOSURE & CONTEXT"
echo ""
echo "This feature was built by the DeviceLab.dev team (https://devicelab.dev)"
echo "to enable parallel testing across distributed device networks."
echo ""
echo "While built for DeviceLab's use case, this feature is useful for anyone"
echo "needing concurrent multi-device testing with Maestro."
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🎯 USE CASES"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "This feature enables concurrent testing on multiple devices for:"
echo ""
echo "  • Distributed testing platforms like DeviceLab.dev"
echo "    (https://devicelab.dev - Private device testing cloud)"
echo ""
echo "  • Local multi-device testing setups"
echo "    (Test on multiple devices/simulators simultaneously)"
echo ""
echo "  • CI/CD pipelines with parallel test execution"
echo "    (Run different test suites in parallel)"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# 1. Fetch latest release
echo "🔍 Finding latest port-feature release..."

LATEST_TAG=$(curl -fsSL "https://api.github.com/repos/$GITHUB_REPO/releases" 2>/dev/null | \
  grep '"tag_name":' | \
  grep -o 'fork-[^"]*' | \
  head -1)

if [ -z "$LATEST_TAG" ]; then
    echo "❌ Could not fetch latest release from GitHub."
    echo "   Please check your internet connection or try again later."
    exit 1
fi

DOWNLOAD_URL="https://github.com/$GITHUB_REPO/releases/download/$LATEST_TAG/maestro-jars.tar.gz"
VERSION=$(echo "$LATEST_TAG" | sed 's/fork-//')

echo "   Latest version: $VERSION"
echo "   Release tag: $LATEST_TAG"
echo ""

# 2. Check maestro installed
if ! command -v maestro >/dev/null 2>&1; then
    echo "❌ Maestro not found. Please install Maestro first:"
    echo "   curl -Ls https://get.maestro.mobile.dev | bash"
    exit 1
fi

# 3. Detect installation
MAESTRO_BIN=$(which maestro)

# Follow symlinks (for Homebrew)
if [ -L "$MAESTRO_BIN" ]; then
    if command -v readlink >/dev/null 2>&1; then
        MAESTRO_BIN=$(readlink -f "$MAESTRO_BIN" 2>/dev/null || readlink "$MAESTRO_BIN")
    fi
fi

MAESTRO_BIN_DIR=$(dirname "$MAESTRO_BIN")
MAESTRO_HOME=$(dirname "$MAESTRO_BIN_DIR")

# Find lib directory
if [ -d "$MAESTRO_HOME/lib" ]; then
    LIB_DIR="$MAESTRO_HOME/lib"
elif [ -d "$MAESTRO_HOME/libexec/lib" ]; then
    LIB_DIR="$MAESTRO_HOME/libexec/lib"
else
    echo "❌ Could not find lib directory"
    exit 1
fi

echo "📍 Detected Maestro installation:"
echo "   Location: $MAESTRO_HOME"
echo "   JARs: $LIB_DIR"
echo ""

# 4. Check current version
CURRENT_CLI_JAR=$(ls "$LIB_DIR"/maestro-cli-*.jar 2>/dev/null | head -1)
if [ -n "$CURRENT_CLI_JAR" ]; then
    CURRENT_VERSION=$(basename "$CURRENT_CLI_JAR" | sed 's/maestro-cli-\(.*\)\.jar/\1/')
    echo "📦 Current version: $CURRENT_VERSION"
else
    echo "📦 Current version: unknown"
fi
echo ""

# 5. Create backup
echo "💾 Creating backup of original JARs..."

# Check for existing backup
if [ -d "$BACKUP_DIR" ]; then
    echo "   ⚠️  Previous backup found. It will be replaced."
    rm -rf "$BACKUP_DIR"
fi

mkdir -p "$BACKUP_DIR"
cp "$LIB_DIR/maestro-"*.jar "$BACKUP_DIR/"

# Count backed up files
BACKUP_COUNT=$(ls "$BACKUP_DIR"/maestro-*.jar 2>/dev/null | wc -l | tr -d ' ')
echo "   ✅ Backed up $BACKUP_COUNT JARs to:"
echo "   $BACKUP_DIR"
echo ""

# 6. Create restore script
cat > "$BACKUP_DIR/restore.sh" << 'RESTORE_SCRIPT'
#!/bin/bash
# Auto-generated restore script

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
echo "🔄 Restoring original Maestro JARs..."
echo ""

# Detect installation (same logic as upgrade script)
if ! command -v maestro >/dev/null 2>&1; then
    echo "❌ Maestro not found in PATH"
    exit 1
fi

MAESTRO_BIN=$(which maestro)

if [ -L "$MAESTRO_BIN" ]; then
    if command -v readlink >/dev/null 2>&1; then
        MAESTRO_BIN=$(readlink -f "$MAESTRO_BIN" 2>/dev/null || readlink "$MAESTRO_BIN")
    fi
fi

MAESTRO_BIN_DIR=$(dirname "$MAESTRO_BIN")
MAESTRO_HOME=$(dirname "$MAESTRO_BIN_DIR")

if [ -d "$MAESTRO_HOME/lib" ]; then
    LIB_DIR="$MAESTRO_HOME/lib"
elif [ -d "$MAESTRO_HOME/libexec/lib" ]; then
    LIB_DIR="$MAESTRO_HOME/libexec/lib"
else
    echo "❌ Could not find lib directory"
    exit 1
fi

echo "📍 Restoring to: $LIB_DIR"
echo ""

# Remove current JARs
echo "🗑️  Removing modified JARs..."
rm "$LIB_DIR/maestro-"*.jar

# Restore backup
echo "📦 Restoring backup..."
cp "$SCRIPT_DIR"/maestro-*.jar "$LIB_DIR/"

echo ""
echo "✅ Restore complete!"
echo "   Maestro restored to original version"
echo ""
RESTORE_SCRIPT

chmod +x "$BACKUP_DIR/restore.sh"

# 7. Save backup info
cat > "$BACKUP_DIR/backup-info.txt" << INFO
Maestro Backup Information
==========================

Created: $(date)
Original Version: $CURRENT_VERSION
Backup Location: $BACKUP_DIR
Installation: $MAESTRO_HOME

Files Backed Up:
$(ls -lh "$BACKUP_DIR"/maestro-*.jar | awk '{print "  " $9 " (" $5 ")"}')

To Restore:
-----------
Option 1 (Easy):
  bash $BACKUP_DIR/restore.sh

Option 2 (Manual):
  rm $LIB_DIR/maestro-*.jar
  cp $BACKUP_DIR/maestro-*.jar $LIB_DIR/

INFO

echo "📝 Backup info saved to:"
echo "   $BACKUP_DIR/backup-info.txt"
echo ""

# 8. Download pre-built JARs
echo "⬇️  Downloading updated JARs with port support..."
TEMP_DIR=$(mktemp -d)

if ! curl -fSL "$DOWNLOAD_URL" -o "$TEMP_DIR/maestro-jars.tar.gz"; then
    echo "❌ Download failed. Your original JARs are safe in:"
    echo "   $BACKUP_DIR"
    rm -rf "$TEMP_DIR"
    exit 1
fi

# 9. Extract
echo "📦 Extracting..."
tar -xzf "$TEMP_DIR/maestro-jars.tar.gz" -C "$TEMP_DIR"

# 10. Verify extracted JARs
NEW_JAR_COUNT=$(ls "$TEMP_DIR"/maestro-*.jar 2>/dev/null | wc -l | tr -d ' ')
if [ "$NEW_JAR_COUNT" -eq 0 ]; then
    echo "❌ No JARs found in download. Backup preserved at:"
    echo "   $BACKUP_DIR"
    rm -rf "$TEMP_DIR"
    exit 1
fi

echo "   Found $NEW_JAR_COUNT new JARs"
echo ""

# 11. Replace JARs
echo "🔄 Installing updated JARs..."
rm "$LIB_DIR/maestro-"*.jar
cp "$TEMP_DIR"/maestro-*.jar "$LIB_DIR/"

# Cleanup temp
rm -rf "$TEMP_DIR"

echo ""
echo "✅ Upgrade complete!"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🎯 NEW FEATURE: Multi-Device Port Support"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Run tests on multiple devices in parallel:"
echo ""
echo "  # Terminal 1"
echo "  maestro --driver-host-port 7005 --device device1 test auth.yaml"
echo ""
echo "  # Terminal 2"
echo "  maestro --driver-host-port 7006 --device device2 test products.yaml"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📦 BACKUP & RESTORE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Your original JARs are backed up at:"
echo "  $BACKUP_DIR"
echo ""
echo "To restore original version:"
echo "  bash ~/.maestro-backups/backup/restore.sh"
echo ""
echo "To view backup details:"
echo "  cat ~/.maestro-backups/backup/backup-info.txt"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📢 IMPORTANT"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "This is a temporary solution. Watch for official Maestro releases"
echo "that include this feature. Once available, restore your backup and"
echo "upgrade to the official version."
echo ""
echo "Verify your version: maestro --version  (should show: 2.0.9-ports)"
echo ""

