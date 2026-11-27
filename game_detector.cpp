// game_detector.cpp

#include "game_detector.h"

namespace gaming_keyboard {

GameDetector::GameDetector(std::shared_ptr<KeymapStore> store,
                            std::shared_ptr<NotifierInterface> notifier)
    : store_(std::move(store)), notifier_(std::move(notifier)) {}

void GameDetector::OnPackageInstalled(const std::string& package_name) {
    // Integrator: call this for fresh installs and reinstalls only. An app
    // update also fires a package-added broadcast (with EXTRA_REPLACING set),
    // and filtering those out avoids a "keymap detected" popup on every update.
    auto existing = store_->Load(package_name);
    if (!existing) return;  // no prior mapping for this package, nothing to do

    notifier_->ShowKeymapDetected(existing->display_name);
}

void GameDetector::OnPackageForegrounded(const std::string& package_name) {
    // Same game resumed (for example after the overlay closes): already armed.
    if (armed_package_ == package_name) return;

    auto existing = store_->Load(package_name);
    if (!existing) {
        if (!armed_package_.empty()) notifier_->ClearOverlayActive();
        armed_package_.clear();
        notified_this_session_ = false;
        return;
    }

    armed_package_ = package_name;
    notifier_->ShowOverlayActive(existing->display_name);
    notified_this_session_ = true;
}

void GameDetector::OnPackageBackgrounded(const std::string& package_name) {
    if (armed_package_ == package_name) {
        armed_package_.clear();
        notified_this_session_ = false;
        notifier_->ClearOverlayActive();
    }
}

}  // namespace gaming_keyboard
