/**
 * @license
 * Copyright 2020 The FOAM Authors. All Rights Reserved.
 * http://www.apache.org/licenses/LICENSE-2.0
 */

 foam.CLASS({
   package: 'foam.nanos.medusa.test',
   name: 'MedusaTestObject',

   documentation: 'FObject solely for the purposes of storing via Medusa.',

   properties: [
     {
       class: 'String',
       name: 'id'
     },
     {
       class: 'String',
       name: 'name'
     },
     {
       class: 'String',
       name: 'description'
     },
   ]
 });
