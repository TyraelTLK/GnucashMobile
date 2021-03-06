/*
 * Copyright (c) 2012 Ngewi Fet <ngewif@gmail.com>
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

package org.gnucash.android.ui.accounts;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.gnucash.android.R;
import org.gnucash.android.db.TransactionsDbAdapter;
import org.gnucash.android.util.OfxFormatter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.ProcessingInstruction;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.Toast;

/**
 * Dialog fragment for exporting account information as OFX files.
 * @author Ngewi Fet <ngewif@gmail.com>
 */
public class ExportDialogFragment extends DialogFragment {
		
	/**
	 * Spinner for selecting destination for the exported file.
	 * The destination could either be SD card, or another application which
	 * accepts files, like Google Drive.
	 */
	Spinner mDestinationSpinner;
	
	/**
	 * Checkbox indicating that all transactions should be exported,
	 * regardless of whether they have been exported previously or not
	 */
	CheckBox mExportAllCheckBox;
	
	/**
	 * Checkbox for deleting all transactions after exporting them
	 */
	CheckBox mDeleteAllCheckBox;
	
	/**
	 * Save button for saving the exported files
	 */
	Button mSaveButton;
	
	/**
	 * Cancels the export dialog
	 */
	Button mCancelButton;
	
	/**
	 * File path for saving the OFX files
	 */
	String mFilePath;
	
	/**
	 * Click listener for positive button in the dialog.
	 * @author Ngewi Fet <ngewif@gmail.com>
	 */
	protected class ExportClickListener implements View.OnClickListener {

		@Override
		public void onClick(View v) {
			boolean exportAll = mExportAllCheckBox.isChecked();
			Document document = null;				
			try {
				document = exportOfx(exportAll);
				writeToExternalStorage(document);
			} catch (Exception e) {
				Log.e(getTag(), e.getMessage());
				Toast.makeText(getActivity(), R.string.error_exporting,
						Toast.LENGTH_LONG).show();
				dismiss();
				return;
			}
			
			int position = mDestinationSpinner.getSelectedItemPosition();
			switch (position) {
			case 0:					
				shareFile(mFilePath);				
				break;

			case 1:				
				File src = new File(mFilePath);
				new File(Environment.getExternalStorageDirectory() + "/gnucash/").mkdirs();
				File dst = new File(Environment.getExternalStorageDirectory() + "/gnucash/" + buildExportFilename());
				
				try {
					copyFile(src, dst);
				} catch (IOException e) {
					Toast.makeText(getActivity(), 
							"Could not write OFX file to :\n" + dst.getAbsolutePath(), 
							Toast.LENGTH_LONG).show();		
					Log.e(getTag(), e.getMessage());
					break;
				}
				
				//file already exists, just let the user know
				Toast.makeText(getActivity(), 
						"OFX file exported to:\n" + dst.getAbsolutePath(), 
						Toast.LENGTH_LONG).show();					
				break;
				
			default:
				break;
			}
			
			if (mDeleteAllCheckBox.isChecked()){
				TransactionsDbAdapter trxnAdapter = new TransactionsDbAdapter(getActivity());
				trxnAdapter.deleteAllTransactions();
				trxnAdapter.close();
			}
			
			Fragment f = getActivity()
			.getSupportFragmentManager()
			.findFragmentByTag(AccountsActivity.FRAGMENT_ACCOUNTS_LIST);
		
			((AccountsListFragment)f).refreshList();
			dismiss();
		}
		
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		return inflater.inflate(R.layout.dialog_export_ofx, container, false);
	}
	
	@Override
	public void onActivityCreated(Bundle savedInstanceState) {		
		super.onActivityCreated(savedInstanceState);
		mFilePath = getActivity().getExternalFilesDir(null) + "/" + buildExportFilename();
		getDialog().setTitle(R.string.menu_export_ofx);
		bindViews();
	}

	/**
	 * Collects references to the UI elements and binds click listeners
	 */
	private void bindViews(){
		View v = getView();
		mDestinationSpinner = (Spinner) v.findViewById(R.id.spinner_export_destination);
		ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(getActivity(),
		        R.array.export_destinations, android.R.layout.simple_spinner_item);		
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);		
		mDestinationSpinner.setAdapter(adapter);
		
		mExportAllCheckBox = (CheckBox) v.findViewById(R.id.checkbox_export_all);
		mDeleteAllCheckBox = (CheckBox) v.findViewById(R.id.checkbox_post_export_delete);
		
		mSaveButton = (Button) v.findViewById(R.id.btn_save);
		mSaveButton.setText(R.string.btn_export);
		mCancelButton = (Button) v.findViewById(R.id.btn_cancel);
		
		mCancelButton.setOnClickListener(new View.OnClickListener() {
			
			@Override
			public void onClick(View v) {				
				dismiss();
			}
		});
		
		mSaveButton.setOnClickListener(new ExportClickListener());
	}
	
	/**
	 * Writes the OFX document <code>doc</code> to external storage
	 * @param Document containing OFX file data
	 * @throws IOException if file could not be saved
	 */
	private void writeToExternalStorage(Document doc) throws IOException{
		File file = new File(mFilePath);
		
		FileWriter writer = new FileWriter(file);
		write(doc, writer);
		
	}
	
	/**
	 * Callback for when the activity chooser dialog is completed
	 */
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		//TODO: fix the exception which is thrown on return
		if (resultCode == Activity.RESULT_OK){
			//uploading or emailing has finished. clean up now.
			File file = new File(mFilePath);
			file.delete();
		}
	}
	
	/**
	 * Starts an intent chooser to allow the user to select an activity to receive
	 * the exported OFX file
	 * @param path String path to the file on disk
	 */
	private void shareFile(String path){
		Intent shareIntent = new Intent(Intent.ACTION_SEND);
		shareIntent.setType("application/xml");
		shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.parse("file://"+ path));
		shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Gnucash OFX export");
		SimpleDateFormat formatter = new SimpleDateFormat("yyyy.MM.dd HH:mm");
		shareIntent.putExtra(Intent.EXTRA_TEXT, "Gnucash accounts export from " 
							+ formatter.format(new Date(System.currentTimeMillis())));
		startActivity(Intent.createChooser(shareIntent, getString(R.string.title_share_ofx_with)));	
	}
	
	/**
	 * Copies a file from <code>src</code> to <code>dst</code>
	 * @param src Absolute path to the source file
	 * @param dst Absolute path to the destination file 
	 * @throws IOException if the file could not be copied
	 */
	public static void copyFile(File src, File dst) throws IOException
	{
		//TODO: Make this asynchronous at some time, t in the future.
	    FileChannel inChannel = new FileInputStream(src).getChannel();
	    FileChannel outChannel = new FileOutputStream(dst).getChannel();
	    try
	    {
	        inChannel.transferTo(0, inChannel.size(), outChannel);
	    }
	    finally
	    {
	        if (inChannel != null)
	            inChannel.close();
	        if (outChannel != null)
	            outChannel.close();
	    }
	}
	
	/**
	 * Builds a file name based on the current time stamp for the exported file
	 * @return String containing the file name
	 */
	public static String buildExportFilename(){
		SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd_HHmm");
		String filename = formatter.format(
				new Date(System.currentTimeMillis())) 
				+ "_gnucash_all.ofx";
		return filename;
	}
	
	/**
	 * Exports transactions in the database to the OFX format.
	 * The accounts are written to a DOM document and returned
	 * @param exportAll Flag to export all transactions or only the new ones since last export
	 * @return DOM {@link Document} containing the OFX file information
	 * @throws ParserConfigurationException
	 */
	protected Document exportOfx(boolean exportAll) throws ParserConfigurationException{		
		DocumentBuilderFactory docFactory = DocumentBuilderFactory
				.newInstance();
		DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

		Document document = docBuilder.newDocument();
		Element root = document.createElement("OFX");
		
		ProcessingInstruction pi = document.createProcessingInstruction("OFX", "OFXHEADER=\"200\" VERSION=\"211\" SECURITY=\"NONE\" OLDFILEUID=\"NONE\" NEWFILEUID=\"NONE\"");
		document.appendChild(pi);		
		document.appendChild(root);
		
		OfxFormatter exporter = new OfxFormatter(getActivity(), exportAll);
		exporter.toXml(document, root);
		
		return document;
	}
	
	/**
	 * Writes out the file held in <code>document</code> to <code>outputWriter</code>
	 * @param document {@link Document} containing the OFX document structure
	 * @param outputWriter {@link Writer} to use in writing the file to stream
	 */
	public void write(Document document, Writer outputWriter){
		try {
			TransformerFactory transformerFactory = TransformerFactory
					.newInstance();
			Transformer transformer = transformerFactory.newTransformer();
			DOMSource source = new DOMSource(document);
			StreamResult result = new StreamResult(outputWriter);
			
			transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			
			transformer.transform(source, result);
		} catch (TransformerConfigurationException txconfigException) {
			txconfigException.printStackTrace();
		} catch (TransformerException tfException) {
			tfException.printStackTrace();
		}
	}
}

