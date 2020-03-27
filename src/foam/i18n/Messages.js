/**
 * @license
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

foam.SCRIPT({
  package: 'foam.i18n',
  name: 'LocaleScript',
  code: function() {
    foam.locale = foam.locale || 'en';
  }
});

foam.CLASS({
  package: 'foam.i18n',
  name: 'ThemeMessage',

  imports: [
    'theme'
  ],

  code: function() {
    foam.appName = 'whitelabel';//TODO should be this.theme.appName
  }
});

foam.CLASS({
  package: 'foam.i18n',
  name: 'MessageAxiom',

  imports: [
    'theme'
  ],

  properties: [
    {
      class: 'String',
      name: 'name'
    },
    {
      class: 'String',
      name: 'description'
    },
    {
      class: 'Object',
      name: 'messageMap',
      help: 'Map of language codes to the message in that language.',
      factory: function() { return {}; }
    },
    {
      class: 'String',
      name: 'message',
      getter: function() {
        return typeof this.message_ === 'object'? this.message_[foam.appName+'-'+foam.locale] ? this.message_[foam.appName+'-'+foam.locale]: this.message_[foam.appName] ? this.message_[foam.appName] : this.messageMap[foam.locale] : this.messageMap[foam.appName];
      },
      setter: function(m) {
        this.message_ = this.messageMap[foam.appName] = this.messageMap[foam.appName+'-'+foam.locale]= m;
      }
    },
    {
      class: 'Simple',
      name: 'message_'
    }
  ],

  methods: [
    function installInClass(cls) {
      Object.defineProperty(
        cls,
        this.name,
        {
          value: this.message,
          configurable: false
        });
    },

    function installInProto(proto) {
      this.installInClass(proto);
    }
  ]
});


foam.CLASS({
  package: 'foam.i18n',
  name: 'MessagesExtension',
  refines: 'foam.core.Model',

  properties: [
    {
      name: 'messages',
      class: 'AxiomArray',
      of: 'foam.i18n.MessageAxiom'
    }
  ]
});
