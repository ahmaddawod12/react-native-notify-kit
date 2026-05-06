/**
 * Copyright (c) 2016-present Invertase Limited & Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this library except in compliance with the License.
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

#import "NotifeeCoreExtensionHelper.h"
#import "Intents/Intents.h"
#import "NotifeeCore.h"
#import "NotifeeCoreUtil.h"

static NSString *const kNoExtension = @"";
static NSString *const kImagePathPrefix = @"image/";

@interface NotifeeCoreExtensionHelper ()
@property(nonatomic, assign) BOOL notificationDelivered;
@end

@implementation NotifeeCoreExtensionHelper
+ (NotifeeCoreExtensionHelper *)instance {
  static dispatch_once_t once;
  static NotifeeCoreExtensionHelper *instance;
  dispatch_once(&once, ^{
    instance = [[self alloc] init];
  });

  return instance;
}

- (NSMutableDictionary *)parseNotifeeOptions:(id)payload {
  if ([payload isKindOfClass:[NSDictionary class]]) {
    return [payload mutableCopy];
  }

  if ([payload isKindOfClass:[NSString class]]) {
    NSData *optionsData = [payload dataUsingEncoding:NSUTF8StringEncoding];
    if (optionsData == nil) {
      NSLog(@"NotifeeCoreExtensionHelper: Could not decode notifee_options string as UTF-8");
      return nil;
    }

    NSError *error = nil;
    id jsonObject = [NSJSONSerialization JSONObjectWithData:optionsData
                                                    options:NSJSONReadingFragmentsAllowed
                                                      error:&error];

    if (error != nil) {
      NSLog(@"NotifeeCoreExtensionHelper: Could not parse notifee_options JSON: %@", error);
      return nil;
    }

    if (![jsonObject isKindOfClass:[NSDictionary class]]) {
      NSLog(@"NotifeeCoreExtensionHelper: Ignoring notifee_options JSON because it is not a "
            @"dictionary: %@",
            NSStringFromClass([jsonObject class]));
      return nil;
    }

    return [jsonObject mutableCopy];
  }

  NSLog(@"NotifeeCoreExtensionHelper: Ignoring notifee_options because it is not a dictionary "
        @"or JSON string: %@",
        NSStringFromClass([payload class]));
  return nil;
}

- (void)populateNotificationContent:(UNNotificationRequest *_Nullable)request
                        withContent:(UNMutableNotificationContent *)content
                 withContentHandler:(void (^)(UNNotificationContent *_Nonnull))contentHandler {
  @synchronized(self) {
    self.contentHandler = [contentHandler copy];
    self.modifiedContent = content;
    self.notificationDelivered = NO;
  }

  id notifeeOptionsPayload = content.userInfo[kPayloadOptionsName];
  if (!notifeeOptionsPayload) {
    [self deliverNotification];
    return;
  }

  // fcm: apns: { payload: {notifee_options: "{}" } }
  NSMutableDictionary *options = [self parseNotifeeOptions:notifeeOptionsPayload];
  if (options == nil) {
    [self deliverNotification];
    return;
  }

  options[@"remote"] = @YES;

  // Convert options to Notification and set defaults
  if (options[@"data"] == nil) {
    options[@"data"] = [NSDictionary dictionary];
  }

  // Pass id to event handler
  if (request != nil && options[@"id"] == nil) {
    options[@"id"] = request.identifier;
  }

  if (options[@"title"] == nil && content.title != nil) {
    options[@"title"] = self.modifiedContent.title;
  }

  if (options[@"body"] == nil) {
    options[@"body"] = self.modifiedContent.body;
  }

  self.modifiedContent = [NotifeeCore buildNotificationContent:options withTrigger:nil];
  [self processCommunicationData:options];
}

- (void)processCommunicationData:(NSMutableDictionary *)options {
  if (options[@"ios"] == nil || options[@"ios"][@"communicationInfo"] == nil) {
    [self handleAttachmentsAndDeliverNotificaiton:options];
    return;
  }

  if (@available(iOS 15.0, *)) {
    NSMutableDictionary *communicationInfo = [options[@"ios"][@"communicationInfo"] mutableCopy];
    communicationInfo[@"body"] = options[@"body"];
    INSendMessageIntent *intent = [NotifeeCoreUtil
        generateSenderIntentForCommunicationNotification:options[@"ios"][@"communicationInfo"]];
    // Use the intent to initialize the interaction.
    INInteraction *interaction = [[INInteraction alloc] initWithIntent:intent response:nil];
    interaction.direction = INInteractionDirectionIncoming;

    NSError *error = nil;
    UNNotificationContent *updatedContent =
        [self.modifiedContent contentByUpdatingWithProvider:intent error:&error];
    if (error) {
      NSLog(@"NotifeeCoreExtensionHelper: Could not update notification content: %@", error);
      [self handleAttachmentsAndDeliverNotificaiton:options];
      return;
    }

    NSLog(@"NotifeeCoreExtensionHelper: Processing communication notification");
    self.modifiedContent = [updatedContent mutableCopy];
    [self handleAttachmentsAndDeliverNotificaiton:options];

    [interaction donateInteractionWithCompletion:^(NSError *error) {
      if (error)
        NSLog(@"NotifeeCoreExtensionHelper: Could not donate interaction for communication "
              @"notification: %@",
              error);
    }];
  } else {
    // Skip, Communication notifications not supported on iOS 15
    [self handleAttachmentsAndDeliverNotificaiton:options];
  }
}

- (void)handleAttachmentsAndDeliverNotificaiton:(NSMutableDictionary *)options {
  NSMutableDictionary *attachmentDict = [NSMutableDictionary new];

  if (options[@"ios"] != nil && options[@"ios"][@"attachments"] != nil &&
      [options[@"ios"][@"attachments"] isKindOfClass:[NSArray class]] &&
      [options[@"ios"][@"attachments"] count] != 0) {
    attachmentDict = options[@"ios"][@"attachments"][0];
  }

  // Check if image url is in payload and parse it if attachmentDict is empty
  NSString *currentImageURL = options[kPayloadOptionsImageURLName];
  if ([attachmentDict count] == 0 && ![currentImageURL isEqual:[NSNull null]] &&
      currentImageURL.length > 1) {
    // make into an attachment dict
    attachmentDict[@"url"] = currentImageURL;
  }

  if ([attachmentDict count] == 0) {
    [self deliverNotification];
    return;
  }

  // Attempt to download attachment
  [self loadAttachment:attachmentDict
      completionHandler:^(UNNotificationAttachment *attachment) {
        if (attachment != nil) {
          self.modifiedContent.attachments = @[ attachment ];
        }

        [self deliverNotification];
      }];
}

- (NSString *)fileExtensionForResponse:(NSURLResponse *)response {
  NSString *suggestedPathExtension = [response.suggestedFilename pathExtension];
  if (suggestedPathExtension.length > 0) {
    return [NSString stringWithFormat:@".%@", suggestedPathExtension];
  }
  if ([response.MIMEType containsString:kImagePathPrefix]) {
    return [response.MIMEType stringByReplacingOccurrencesOfString:kImagePathPrefix
                                                        withString:@"."];
  }
  return kNoExtension;
}

- (void)loadAttachment:(NSDictionary *)attachmentDict
     completionHandler:(void (^)(UNNotificationAttachment *))completionHandler {
  @try {
    __block UNNotificationAttachment *attachment = nil;
    NSString *attachmentIdentifier = attachmentDict[@"id"];
    NSURL *attachmentURL = [NSURL URLWithString:attachmentDict[@"url"]];

    // NSE has a ~30-second budget before iOS calls serviceExtensionTimeWillExpire
    // and kills the process. Cap the download at 25 seconds to leave a 5-second
    // margin for graceful fallback via the extension's expiration handler.
    NSURLSessionConfiguration *config = [NSURLSessionConfiguration defaultSessionConfiguration];
    config.timeoutIntervalForRequest = 25.0;
    config.timeoutIntervalForResource = 25.0;
    NSURLSession *session = [NSURLSession sessionWithConfiguration:config];
    [[session
        downloadTaskWithURL:attachmentURL
          completionHandler:^(NSURL *temporaryFileLocation, NSURLResponse *response,
                              NSError *error) {
            if (error != nil) {
              NSLog(
                  @"NotifeeCoreExtensionHelper: An exception occurred while attempting to download "
                  @"image with URL %@: "
                  @"%@",
                  attachmentURL, error);
              completionHandler(attachment);
              return;
            }

            NSFileManager *fileManager = [NSFileManager defaultManager];
            NSString *fileExtension = [self fileExtensionForResponse:response];
            NSURL *localURL = [NSURL
                fileURLWithPath:[temporaryFileLocation.path stringByAppendingString:fileExtension]];
            [fileManager moveItemAtURL:temporaryFileLocation toURL:localURL error:&error];
            if (error) {
              NSLog(@"NotifeeCoreExtensionHelper: Failed to move the image file to local location: "
                    @"%@, error %@",
                    localURL, error);
              completionHandler(attachment);
              return;
            }

            attachment = [UNNotificationAttachment
                attachmentWithIdentifier:attachmentIdentifier
                                     URL:localURL
                                 options:[NotifeeCoreUtil
                                             attachmentOptionsFromDictionary:attachmentDict]
                                   error:&error];
            if (error) {
              NSLog(
                  @"NotifeeCoreExtensionHelper: Failed to create attachment with URL: %@, error %@",
                  localURL, error);
              completionHandler(attachment);
              return;
            }
            completionHandler(attachment);
          }] resume];
  } @catch (NSException *exception) {
    NSLog(@"NotifeeCoreExtensionHelper: Failed to create attachment: %@, error %@", attachmentDict,
          exception.reason);
    completionHandler(nil);
  }
}

- (void)deliverNotification {
  void (^contentHandler)(UNNotificationContent *) = nil;
  UNNotificationContent *modifiedContent = nil;

  @synchronized(self) {
    if (self.notificationDelivered || self.contentHandler == nil) {
      return;
    }

    contentHandler = [self.contentHandler copy];
    modifiedContent = self.modifiedContent;
    self.notificationDelivered = YES;
    self.contentHandler = nil;
    self.modifiedContent = nil;
  }

  if (contentHandler != nil && modifiedContent != nil) {
    contentHandler(modifiedContent);
  }
}

@end
