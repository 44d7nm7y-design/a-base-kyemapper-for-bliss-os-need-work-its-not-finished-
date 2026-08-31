// game_detector.cpp

#include "game_detector.h"

namespace gaming_keyboard {

GameDetector::GameDetector(std::shared_ptr<KeymapStore> store,
                            std::shared_ptr<NotifierInterface> notifier)
    : store_(std::move(store)), notifier_(std::move(notifier)) {}

void GameDetector::OnPackageInstalled(const std::string& package_name) {
    auto existing = store_->Load(package_name);
    if (!existing) return;  // no prior mapping for this package — nothing to do

    notifier_->ShowKeymapDetected(existing->display_name);
}

void GameDetector::OnPackageForegrounded(const std::string& package_name) {
    auto existing = store_->Load(package_name);
    if (!existing) {
        armed_package_.clear();
        return;
    }

    armed_package_ = package_name;
    notified_this_session_ = false;

    if (!notified_this_session_) {
        notifier_->ShowOverlayActive(existing->display_name);
        notified_this_session_ = true;
    }
}

void GameDetector::OnPackageBackgrounded(const std::string& package_name) {
    if (armed_package_ == package_name) {
        armed_package_.clear();
        notified_this_session_ = false;
    }
}

}  // namespace gaming_keyboard
