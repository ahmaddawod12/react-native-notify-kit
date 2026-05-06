/**
 * Copyright (c) 2016-present Invertase Limited & Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

#import <Foundation/Foundation.h>
#import <UserNotifications/UserNotifications.h>
#import <objc/runtime.h>

#import "NotifeeCore+UNUserNotificationCenter.h"
#import "NotifeeCoreDelegateHolder.h"
#import "NotifeeCoreUtil.h"

static NSInteger gFailures = 0;

@interface HarnessUserNotificationCenter : NSObject
@property(nonatomic, weak) id<UNUserNotificationCenterDelegate> delegate;
@end

@implementation HarnessUserNotificationCenter
@end

static HarnessUserNotificationCenter *gHarnessCenter = nil;

static void HarnessInstallUserNotificationCenterStub(void) {
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    gHarnessCenter = [HarnessUserNotificationCenter new];

    Method method =
        class_getClassMethod([UNUserNotificationCenter class], @selector(currentNotificationCenter));
    if (method == NULL) {
      gFailures += 1;
      fprintf(stderr,
              "[delegate-chaining] FAIL install-center-stub: currentNotificationCenter not found\n");
      return;
    }

    IMP replacement = imp_implementationWithBlock(^UNUserNotificationCenter *(id receiver) {
      (void)receiver;
      return (UNUserNotificationCenter *)(id)gHarnessCenter;
    });
    method_setImplementation(method, replacement);
  });
}

@implementation NotifeeCore

+ (void)topUpRollingTimestampTriggersWithCompletion:(void (^)(NSError *error))completion {
  if (completion != nil) {
    completion(nil);
  }
}

@end

@implementation NotifeeCoreDelegateHolder

+ (instancetype)instance {
  static dispatch_once_t once;
  __strong static NotifeeCoreDelegateHolder *sharedInstance;
  dispatch_once(&once, ^{
    sharedInstance = [[NotifeeCoreDelegateHolder alloc] init];
    sharedInstance.pendingEvents = [NSMutableArray new];
  });
  return sharedInstance;
}

- (void)didReceiveNotifeeCoreEvent:(NSDictionary *)event {
  if (event != nil) {
    [self.pendingEvents addObject:event];
  }
}

@end

@implementation NotifeeCoreUtil

+ (BOOL)isRollingTimestampTrigger:(NSDictionary *)triggerDict {
  (void)triggerDict;
  return NO;
}

+ (BOOL)isRollingInternalNotificationId:(NSString *)notificationId {
  (void)notificationId;
  return NO;
}

+ (NSDictionary *)parseUNNotificationRequest:(UNNotificationRequest *)request {
  (void)request;
  return nil;
}

@end

@interface HarnessNotification : NSObject
@property(nonatomic, strong) UNNotificationRequest *request;
@end

@implementation HarnessNotification
@end

@interface HarnessNotificationResponse : NSObject
@property(nonatomic, strong) id notification;
@property(nonatomic, copy) NSString *actionIdentifier;
@end

@implementation HarnessNotificationResponse
@end

@interface HarnessThirdPartyDelegate : NSObject <UNUserNotificationCenterDelegate>
@property(nonatomic, copy) NSString *name;
@property(nonatomic, assign) NSInteger willPresentCount;
@property(nonatomic, assign) NSInteger didReceiveCount;
@property(nonatomic, assign) NSInteger openSettingsCount;
@property(nonatomic, assign) BOOL callCompletion;
@property(nonatomic, assign) UNNotificationPresentationOptions presentationOptions;
@end

@implementation HarnessThirdPartyDelegate

- (instancetype)initWithName:(NSString *)name {
  self = [super init];
  if (self != nil) {
    _name = [name copy];
    _callCompletion = YES;
    _presentationOptions = UNNotificationPresentationOptionSound;
  }
  return self;
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:
             (void (^)(UNNotificationPresentationOptions options))completionHandler {
  (void)center;
  (void)notification;
  self.willPresentCount += 1;
  if (self.callCompletion && completionHandler != nil) {
    completionHandler(self.presentationOptions);
  }
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
    didReceiveNotificationResponse:(UNNotificationResponse *)response
             withCompletionHandler:(void (^)(void))completionHandler {
  (void)center;
  (void)response;
  self.didReceiveCount += 1;
  if (self.callCompletion && completionHandler != nil) {
    completionHandler();
  }
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
    openSettingsForNotification:(UNNotification *)notification {
  (void)center;
  (void)notification;
  self.openSettingsCount += 1;
}

@end

static void HarnessFail(NSString *testName, NSString *message) {
  gFailures += 1;
  fprintf(stderr, "[delegate-chaining] FAIL %s: %s\n", testName.UTF8String,
          message.UTF8String);
}

static void HarnessPass(NSString *testName) {
  fprintf(stdout, "[delegate-chaining] PASS %s\n", testName.UTF8String);
}

static void HarnessFinishTest(NSString *testName, NSInteger failuresBefore) {
  if (gFailures == failuresBefore) {
    HarnessPass(testName);
  }
}

static void HarnessAssert(BOOL condition, NSString *testName, NSString *message) {
  if (!condition) {
    HarnessFail(testName, message);
  }
}

static UNNotificationRequest *HarnessNonNotifeeRequest(NSString *identifier) {
  UNMutableNotificationContent *content = [UNMutableNotificationContent new];
  content.title = @"delegate chaining harness";
  content.body = @"non-Notifee notification";
  content.userInfo = @{};
  return [UNNotificationRequest requestWithIdentifier:identifier content:content trigger:nil];
}

static UNNotification *HarnessNonNotifeeNotification(NSString *identifier) {
  HarnessNotification *notification = [HarnessNotification new];
  notification.request = HarnessNonNotifeeRequest(identifier);
  return (UNNotification *)(id)notification;
}

static UNNotificationResponse *HarnessNonNotifeeResponse(NSString *identifier) {
  HarnessNotificationResponse *response = [HarnessNotificationResponse new];
  response.notification = HarnessNonNotifeeNotification(identifier);
  response.actionIdentifier = UNNotificationDefaultActionIdentifier;
  return (UNNotificationResponse *)(id)response;
}

static void HarnessAssertCurrentDelegate(id actualDelegate, id expectedDelegate, NSString *testName,
                                         NSString *message) {
  HarnessAssert(actualDelegate == expectedDelegate, testName, message);
}

static void HarnessTestCapturesExistingDelegate(UNUserNotificationCenter *center,
                                                NotifeeCoreUNUserNotificationCenter *notifeeCenter,
                                                HarnessThirdPartyDelegate *existingDelegate) {
  NSString *testName = @"captures-existing-delegate";
  NSInteger failuresBefore = gFailures;

  HarnessAssertCurrentDelegate(center.delegate, notifeeCenter, testName,
                               @"NotifyKit did not become the current delegate");
  HarnessAssert(notifeeCenter.originalDelegate == existingDelegate, testName,
                @"NotifyKit did not retain the pre-existing delegate as originalDelegate");

  HarnessFinishTest(testName, failuresBefore);
}

static void HarnessTestForwardsNonNotifeeWillPresent(
    UNUserNotificationCenter *center, HarnessThirdPartyDelegate *existingDelegate) {
  NSString *testName = @"forwards-non-notifee-will-present";
  NSInteger failuresBefore = gFailures;
  NSInteger existingWillPresentBefore = existingDelegate.willPresentCount;
  __block NSInteger completionCount = 0;
  __block UNNotificationPresentationOptions receivedOptions = UNNotificationPresentationOptionNone;

  id<UNUserNotificationCenterDelegate> currentDelegate = center.delegate;
  [currentDelegate userNotificationCenter:center
                  willPresentNotification:HarnessNonNotifeeNotification(@"will-present")
                    withCompletionHandler:^(UNNotificationPresentationOptions options) {
                      completionCount += 1;
                      receivedOptions = options;
                    }];

  HarnessAssert(existingDelegate.willPresentCount == existingWillPresentBefore + 1, testName,
                @"pre-existing delegate did not receive willPresentNotification");
  HarnessAssert(completionCount == 1, testName,
                @"willPresentNotification completion was not called exactly once");
  HarnessAssert(receivedOptions == existingDelegate.presentationOptions, testName,
                @"willPresentNotification did not return options from original delegate");

  HarnessFinishTest(testName, failuresBefore);
}

static void HarnessTestForwardsNonNotifeeDidReceive(
    UNUserNotificationCenter *center, HarnessThirdPartyDelegate *existingDelegate) {
  NSString *testName = @"forwards-non-notifee-did-receive";
  NSInteger failuresBefore = gFailures;
  NSInteger existingDidReceiveBefore = existingDelegate.didReceiveCount;
  __block NSInteger completionCount = 0;

  id<UNUserNotificationCenterDelegate> currentDelegate = center.delegate;
  [currentDelegate userNotificationCenter:center
           didReceiveNotificationResponse:HarnessNonNotifeeResponse(@"did-receive")
                    withCompletionHandler:^{
                      completionCount += 1;
                    }];

  HarnessAssert(existingDelegate.didReceiveCount == existingDidReceiveBefore + 1, testName,
                @"pre-existing delegate did not receive didReceiveNotificationResponse");
  HarnessAssert(completionCount == 1, testName,
                @"didReceiveNotificationResponse completion was not called exactly once");

  HarnessFinishTest(testName, failuresBefore);
}

static void HarnessTestForwardsOpenSettings(UNUserNotificationCenter *center,
                                            HarnessThirdPartyDelegate *existingDelegate) {
  NSString *testName = @"forwards-open-settings";
  NSInteger failuresBefore = gFailures;
  NSInteger existingOpenSettingsBefore = existingDelegate.openSettingsCount;

  id<UNUserNotificationCenterDelegate> currentDelegate = center.delegate;
  [currentDelegate userNotificationCenter:center
              openSettingsForNotification:HarnessNonNotifeeNotification(@"open-settings")];

  HarnessAssert(existingDelegate.openSettingsCount == existingOpenSettingsBefore + 1, testName,
                @"pre-existing delegate did not receive openSettingsForNotification");

  HarnessFinishTest(testName, failuresBefore);
}

static void HarnessTestCompletionCalledOnceInCoveredPaths(
    UNUserNotificationCenter *center, HarnessThirdPartyDelegate *existingDelegate) {
  NSString *testName = @"completion-called-once";
  NSInteger failuresBefore = gFailures;
  NSInteger existingWillPresentBefore = existingDelegate.willPresentCount;
  NSInteger existingDidReceiveBefore = existingDelegate.didReceiveCount;
  __block NSInteger willPresentCompletionCount = 0;
  __block NSInteger didReceiveCompletionCount = 0;

  id<UNUserNotificationCenterDelegate> currentDelegate = center.delegate;
  [currentDelegate userNotificationCenter:center
                  willPresentNotification:HarnessNonNotifeeNotification(@"completion-will-present")
                    withCompletionHandler:^(UNNotificationPresentationOptions options) {
                      (void)options;
                      willPresentCompletionCount += 1;
                    }];
  [currentDelegate userNotificationCenter:center
           didReceiveNotificationResponse:HarnessNonNotifeeResponse(@"completion-did-receive")
                    withCompletionHandler:^{
                      didReceiveCompletionCount += 1;
                    }];

  HarnessAssert(existingDelegate.willPresentCount == existingWillPresentBefore + 1, testName,
                @"willPresentNotification did not forward during completion one-shot check");
  HarnessAssert(existingDelegate.didReceiveCount == existingDidReceiveBefore + 1, testName,
                @"didReceiveNotificationResponse did not forward during completion one-shot check");
  HarnessAssert(willPresentCompletionCount == 1, testName,
                @"willPresentNotification completion was called more or less than once");
  HarnessAssert(didReceiveCompletionCount == 1, testName,
                @"didReceiveNotificationResponse completion was called more or less than once");

  HarnessFinishTest(testName, failuresBefore);
}

static void HarnessTestLateDelegateOverridesNotifyKit(
    UNUserNotificationCenter *center, NotifeeCoreUNUserNotificationCenter *notifeeCenter,
    HarnessThirdPartyDelegate *lateDelegate, HarnessThirdPartyDelegate *existingDelegate) {
  NSString *testName = @"late-delegate-overrides-notifykit";
  NSInteger failuresBefore = gFailures;
  NSInteger lateWillPresentBefore = lateDelegate.willPresentCount;
  NSInteger existingWillPresentBefore = existingDelegate.willPresentCount;
  __block NSInteger completionCount = 0;

  center.delegate = lateDelegate;

  HarnessAssertCurrentDelegate(center.delegate, lateDelegate, testName,
                               @"late delegate did not become current delegate");
  HarnessAssert(center.delegate != notifeeCenter, testName,
                @"NotifyKit unexpectedly remained the current delegate after late override");

  id<UNUserNotificationCenterDelegate> currentDelegate = center.delegate;
  [currentDelegate userNotificationCenter:center
                  willPresentNotification:HarnessNonNotifeeNotification(@"late-will-present")
                    withCompletionHandler:^(UNNotificationPresentationOptions options) {
                      (void)options;
                      completionCount += 1;
                    }];

  HarnessAssert(lateDelegate.willPresentCount == lateWillPresentBefore + 1, testName,
                @"late delegate did not receive willPresentNotification");
  HarnessAssert(existingDelegate.willPresentCount == existingWillPresentBefore, testName,
                @"pre-existing delegate changed, suggesting NotifyKit stayed in the callback path");
  HarnessAssert(completionCount == 1, testName,
                @"late delegate willPresentNotification completion was not called exactly once");

  HarnessFinishTest(testName, failuresBefore);
}

static void HarnessTestHandleRemoteFlagDoesNotRechain(
    UNUserNotificationCenter *center, NotifeeCoreUNUserNotificationCenter *notifeeCenter,
    HarnessThirdPartyDelegate *lateDelegate, HarnessThirdPartyDelegate *existingDelegate) {
  NSString *testName = @"handle-remote-flag-does-not-rechain";
  NSInteger failuresBefore = gFailures;
  NSInteger lateDidReceiveBefore = lateDelegate.didReceiveCount;
  NSInteger existingDidReceiveBefore = existingDelegate.didReceiveCount;
  __block NSInteger completionCount = 0;

  notifeeCenter.shouldHandleRemoteNotifications = NO;

  HarnessAssert(notifeeCenter.shouldHandleRemoteNotifications == NO, testName,
                @"handleRemoteNotifications flag did not switch off on NotifyKit delegate");
  HarnessAssertCurrentDelegate(center.delegate, lateDelegate, testName,
                               @"handleRemoteNotifications flag unexpectedly changed current delegate");
  HarnessAssert(center.delegate != notifeeCenter, testName,
                @"NotifyKit unexpectedly became current delegate after handleRemoteNotifications=false");

  id<UNUserNotificationCenterDelegate> currentDelegate = center.delegate;
  [currentDelegate userNotificationCenter:center
           didReceiveNotificationResponse:HarnessNonNotifeeResponse(@"late-did-receive")
                    withCompletionHandler:^{
                      completionCount += 1;
                    }];

  HarnessAssert(lateDelegate.didReceiveCount == lateDidReceiveBefore + 1, testName,
                @"late delegate did not keep didReceiveNotificationResponse after flag change");
  HarnessAssert(existingDelegate.didReceiveCount == existingDidReceiveBefore, testName,
                @"pre-existing delegate changed, suggesting NotifyKit re-entered the callback path");
  HarnessAssert(completionCount == 1, testName,
                @"late delegate didReceiveNotificationResponse completion was not called exactly once");

  HarnessFinishTest(testName, failuresBefore);
}

int main(void) {
  @autoreleasepool {
    HarnessInstallUserNotificationCenterStub();
    if (gFailures > 0) {
      return 1;
    }

    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    center.delegate = nil;

    HarnessThirdPartyDelegate *existingDelegate =
        [[HarnessThirdPartyDelegate alloc] initWithName:@"existing"];
    HarnessThirdPartyDelegate *lateDelegate =
        [[HarnessThirdPartyDelegate alloc] initWithName:@"late"];

    center.delegate = existingDelegate;

    NotifeeCoreUNUserNotificationCenter *notifeeCenter =
        [NotifeeCoreUNUserNotificationCenter instance];
    [notifeeCenter observe];

    HarnessTestCapturesExistingDelegate(center, notifeeCenter, existingDelegate);
    HarnessTestForwardsNonNotifeeWillPresent(center, existingDelegate);
    HarnessTestForwardsNonNotifeeDidReceive(center, existingDelegate);
    HarnessTestForwardsOpenSettings(center, existingDelegate);
    HarnessTestCompletionCalledOnceInCoveredPaths(center, existingDelegate);
    HarnessTestLateDelegateOverridesNotifyKit(center, notifeeCenter, lateDelegate,
                                              existingDelegate);
    HarnessTestHandleRemoteFlagDoesNotRechain(center, notifeeCenter, lateDelegate,
                                              existingDelegate);

    center.delegate = nil;
  }

  if (gFailures > 0) {
    fprintf(stderr, "[delegate-chaining] FAIL %ld harness failure(s)\n", (long)gFailures);
    return 1;
  }

  fprintf(stdout, "[delegate-chaining] PASS all\n");
  return 0;
}
