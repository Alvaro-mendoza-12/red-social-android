"""Source contract regressions for sparse/offline review; these do not replace Android tests."""
import pathlib
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[2]

def source(path):
    return (ROOT / path).read_text()

class AndroidAuditContracts(unittest.TestCase):
    def test_realtime_queue_is_owned_per_session(self):
        code = source('app/src/main/java/conectate/com/data/repository/message/MessageRealtimeSync.kt')
        self.assertIn('private class RealtimeSession', code)
        self.assertIn('database.withTransaction', code)
        self.assertIn('authService.withAccountLocalStorage(session.owner)', code)
        self.assertNotIn('private val messageChannel', code)
        self.assertIn('catch (cancelled: CancellationException)', code)

    def test_realtime_envelope_retains_provenance(self):
        code = source('app/src/main/java/conectate/com/data/localfallback/UbuntuRealtimeClient.kt')
        self.assertIn('val connectionGeneration: Long', code)
        self.assertIn('val subscriptionGeneration: Long', code)
        self.assertIn('fun isCurrent(envelope: UbuntuRealtimeEnvelope)', code)
        subscription = code[code.index('fun replaceUserSubscription'):code.index('fun clearUserSubscriptions')]
        self.assertIn('reconnect()', subscription)
        self.assertIn('_events.filter(::isCurrent)', code)

    def test_admitted_realtime_events_are_bound_to_visit_not_transport(self):
        code = source('app/src/main/java/conectate/com/data/localfallback/UbuntuRealtimeClient.kt')
        predicate = code[code.index('fun isCurrent(envelope:'):code.index('@Synchronized', code.index('fun isCurrent(envelope:'))]
        self.assertNotIn('socketGeneration', predicate)
        self.assertIn('envelope.subscriptionGeneration == userSubscriptionGeneration', predicate)
        self.assertIn('val connectionSubscriptionGeneration = userSubscriptionGeneration', code)
        self.assertIn('publishAcceptedEnvelope', code)

    def test_live_collectors_register_before_subscribing_to_filtered_events(self):
        client = source('app/src/main/java/conectate/com/data/localfallback/UbuntuRealtimeClient.kt')
        live = source('app/src/main/java/conectate/com/data/repository/LiveRepository.kt')
        self.assertIn('_events.onSubscription(action).filter(::isCurrent)', client)
        self.assertNotIn('ubuntuRealtimeClient.events.onSubscription', live)
        self.assertEqual(2, live.count('ubuntuRealtimeClient.eventsOnSubscription'))

    def test_profile_save_omits_other_sections(self):
        code = source('app/src/main/java/conectate/com/presentation/profile/EditProfileScreen.kt')
        for field in ('name = trimmedName', 'bio = trimmedBio', 'country = country.trim()', 'city = city.trim()', 'gender = gender'):
            self.assertIn(field + '.takeIf { currentSection == ProfileEditSection.BASIC }', code)
        vm = source('app/src/main/java/conectate/com/presentation/profile/ProfileViewModel.kt')
        self.assertIn('name: String?', vm)
        self.assertIn('bio = updateRequest.bio ?: currentUser.bio', vm)

    def test_profile_discard_uses_canonical_social_link_comparison(self):
        code = source('app/src/main/java/conectate/com/presentation/profile/EditProfileScreen.kt')
        self.assertIn('profileSocialDraftHasChanges(', code)
        self.assertNotIn('whatsapp != savedUser.social_links', code)
        helper = source('app/src/main/java/conectate/com/presentation/profile/ProfileSocialDraft.kt')
        self.assertIn('!ProfileSocialLinks.isValidInput(platform, savedValue)', helper)

    def test_failed_profile_music_save_keeps_the_draft_file(self):
        code = source('app/src/main/java/conectate/com/presentation/profile/ProfileViewModel.kt')
        self.assertIn('if (profileMusicSaved) deleteTemporaryClip()', code)

    def test_profile_back_and_birthdate_are_explicit(self):
        code = source('app/src/main/java/conectate/com/presentation/profile/EditProfileScreen.kt')
        self.assertIn('BackHandler', code)
        self.assertIn('selectableDates =', code)
        self.assertIn('rememberSaveable(draftAccountId)', code)

    def test_accessible_profile_tabs(self):
        code = source('app/src/main/java/conectate/com/presentation/profile/ProfileScreen.kt')
        start = code.index('// 3. STICKY TABS')
        tabs = code[start:code.index('// 4. Tab Content', start)]
        self.assertIn('Role.Tab', tabs)
        self.assertNotIn('Color.Gray', tabs)

    def test_ci_bootstraps_google_services_and_debug_signing(self):
        for workflow in ('android-ci.yml', 'android-instrumented-smoke.yml'):
            code = source('.github/workflows/' + workflow)
            self.assertIn('python tools/prepare_android_ci.py', code)
        production = source('.github/workflows/android-production-release.yml')
        self.assertIn('ANDROID_GOOGLE_SERVICES_JSON_BASE64', production)
        self.assertIn('--production', production)

    def test_release_origin_has_runtime_bounds(self):
        code = source('app/build.gradle.kts')
        validator = code[code.index('fun String.isStableHttpsBackendOrigin'):code.index('fun String.isDnsDiscoveryHost')]
        for required in ('uri.rawQuery == null', 'uri.rawFragment == null', 'uri.rawPath', '443', '.ts.net'):
            self.assertIn(required, validator)

if __name__ == '__main__': unittest.main()
